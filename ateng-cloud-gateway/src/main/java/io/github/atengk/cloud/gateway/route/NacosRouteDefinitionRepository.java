package io.github.atengk.cloud.gateway.route;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.ConfigType;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.properties.AtengGatewayDynamicRouteProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 Nacos 配置中心的动态路由持久化仓库与快照守卫热更新实现
 *
 * <p>微服务专属架构铁律落地：
 * <ul>
 *     <li><b>原生 SPI 对齐</b>：实现 Spring Cloud Gateway 原生 {@link RouteDefinitionRepository}；</li>
 *     <li><b>动态原子热更</b>：基于 {@link AtomicReference} 实现无锁高并发安全热替换并发布 {@link RefreshRoutesEvent}；</li>
 *     <li><b>快照守卫容错机制 (Keep Last-Known Good Snapshot)</b>：反序列化或语义语法校验失败时坚决保留有效快照，杜绝整网 404 瘫痪；</li>
 *     <li><b>静态路由安全兜底</b>：仅管理 Nacos 动态路由，与本地 YAML 静态路由共存不互斥；</li>
 *     <li><b>确定性生命周期</b>：初始化自动注册 Nacos 监听器，销毁时确定性注销清理，杜绝会话泄露。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class NacosRouteDefinitionRepository implements RouteDefinitionRepository,
        InitializingBean, DisposableBean, ApplicationEventPublisherAware {

    private static final Logger log = LoggerFactory.getLogger(NacosRouteDefinitionRepository.class);

    private final ObjectProvider<NacosConfigManager> nacosConfigManagerProvider;
    private final AtengGatewayDynamicRouteProperties properties;
    private final ObjectMapper objectMapper;

    private ApplicationEventPublisher applicationEventPublisher;
    private ConfigService configService;
    private Listener nacosListener;

    /**
     * 内存路由表快照 (routeId -> RouteDefinition)，保证原子热替换与并发读取一致性
     */
    private final AtomicReference<Map<String, RouteDefinition>> routesSnapshot =
            new AtomicReference<>(Collections.emptyMap());

    public NacosRouteDefinitionRepository(
            @Nullable ObjectProvider<NacosConfigManager> nacosConfigManagerProvider,
            AtengGatewayDynamicRouteProperties properties,
            ObjectMapper objectMapper,
            @Nullable ApplicationEventPublisher applicationEventPublisher) {
        this.nacosConfigManagerProvider = nacosConfigManagerProvider;
        this.properties = properties != null ? properties : new AtengGatewayDynamicRouteProperties();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(routesSnapshot.get().values());
    }

    @Override
    public Mono<Void> save(Mono<RouteDefinition> route) {
        return route.flatMap(r -> {
            if (r == null || !StringUtils.hasText(r.getId())) {
                return Mono.error(new IllegalArgumentException("RouteDefinition 或其 ID 不能为空"));
            }
            try {
                validateRouteDefinition(r);
            } catch (Exception ex) {
                return Mono.error(ex);
            }
            routesSnapshot.updateAndGet(current -> {
                Map<String, RouteDefinition> next = new LinkedHashMap<>(current);
                next.put(r.getId(), r);
                return Collections.unmodifiableMap(next);
            });
            persistSnapshotToNacos();
            publishRefreshEvent();
            return Mono.empty();
        });
    }

    @Override
    public Mono<Void> delete(Mono<String> routeId) {
        return routeId.flatMap(id -> {
            if (!StringUtils.hasText(id)) {
                return Mono.error(new IllegalArgumentException("RouteDefinition ID 不能为空"));
            }
            AtomicBoolean removed = new AtomicBoolean(false);
            routesSnapshot.updateAndGet(current -> {
                if (!current.containsKey(id)) {
                    return current;
                }
                Map<String, RouteDefinition> next = new LinkedHashMap<>(current);
                next.remove(id);
                removed.set(true);
                return Collections.unmodifiableMap(next);
            });
            if (removed.get()) {
                persistSnapshotToNacos();
                publishRefreshEvent();
                return Mono.empty();
            } else {
                return Mono.defer(() -> Mono.error(new NotFoundException("RouteDefinition not found: " + id)));
            }
        });
    }

    /**
     * 初始化 Nacos 配置读取与动态监听器注册
     */
    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            log.info("[Gateway] Nacos 动态路由已关闭 (ateng.cloud.gateway.dynamic-route.enabled=false)");
            return;
        }

        NacosConfigManager configManager = nacosConfigManagerProvider != null
                ? nacosConfigManagerProvider.getIfAvailable() : null;
        if (configManager == null) {
            log.info("[Gateway] NacosConfigManager 未注入或 Nacos 配置中心已关闭，跳过 Nacos 动态路由监听器注册");
            return;
        }

        try {
            ConfigService service = configManager.getConfigService();
            if (service == null) {
                log.warn("[Gateway] ConfigService 获取为 null，跳过 Nacos 动态路由监听器注册");
                return;
            }
            this.configService = service;

            // 1. 读取 Nacos 初始路由配置 (使用配置强类型超时)
            long timeoutMillis = properties.getTimeout() != null ? properties.getTimeout().toMillis() : 5000L;
            String initialConfig = service.getConfig(
                    properties.getDataId(), properties.getGroup(), timeoutMillis);
            if (StringUtils.hasText(initialConfig)) {
                log.info("[Gateway] 读取到 Nacos 动态路由初始配置: dataId={}, group={}",
                        properties.getDataId(), properties.getGroup());
                publishRoutes(initialConfig, false);
            } else {
                log.info("[Gateway] Nacos 动态路由初始配置为空: dataId={}, group={}",
                        properties.getDataId(), properties.getGroup());
            }

            // 2. 注册 Nacos 配置变更监听器
            this.nacosListener = new AbstractListener() {
                @Override
                public void receiveConfigInfo(String newConfigInfo) {
                    log.info("[Gateway] 收到 Nacos 动态路由配置变更通知: dataId={}, group={}",
                            properties.getDataId(), properties.getGroup());
                    publishRoutes(newConfigInfo, true);
                }
            };
            service.addListener(properties.getDataId(), properties.getGroup(), this.nacosListener);
            log.info("[Gateway] Nacos 动态路由监听器注册成功: dataId={}, group={}",
                    properties.getDataId(), properties.getGroup());
        } catch (Exception ex) {
            log.error("[Gateway] 初始化 Nacos 动态路由监听异常: dataId={}, group={}, error={}",
                    properties.getDataId(), properties.getGroup(), ex.getMessage(), ex);
        }
    }

    /**
     * 容器销毁时安全注销 Nacos 监听器
     */
    @Override
    public void destroy() {
        if (this.configService != null && this.nacosListener != null) {
            try {
                this.configService.removeListener(
                        properties.getDataId(), properties.getGroup(), this.nacosListener);
                log.info("[Gateway] Nacos 动态路由监听器注销成功: dataId={}, group={}",
                        properties.getDataId(), properties.getGroup());
            } catch (Exception ex) {
                log.warn("[Gateway] 注销 Nacos 动态路由监听器异常: {}", ex.getMessage());
            }
        }
    }

    /**
     * 手动/监听触发动态路由原子更新与快照守卫校验
     *
     * @param configInfo Nacos 推送的 JSON 路由配置文本
     * @return 若更新成功返回 true；若触发快照守卫回退则返回 false
     */
    public boolean updateRoutes(String configInfo) {
        return publishRoutes(configInfo, true);
    }

    /**
     * 执行路由解析、语法校验与原子快照替换
     *
     * @param configInfo JSON 路由配置文本
     * @param publishEvent 是否发布 RefreshRoutesEvent 事件
     * @return 校验与更新是否成功
     */
    public synchronized boolean publishRoutes(String configInfo, boolean publishEvent) {
        if (!StringUtils.hasText(configInfo)) {
            log.warn("[Gateway] 动态路由配置内容为空，触发快照守卫，坚决保留现有快照: dataId={}, group={}",
                    properties.getDataId(), properties.getGroup());
            return false;
        }

        try {
            // 1. 反序列化
            List<RouteDefinition> newRoutes = parseRouteDefinitions(configInfo);

            // 2. 深度语法语义校验
            validateRouteDefinitions(newRoutes);

            // 3. 构造新的不可变快照映射
            Map<String, RouteDefinition> newMap = new LinkedHashMap<>();
            for (RouteDefinition route : newRoutes) {
                newMap.put(route.getId(), route);
            }

            // 4. 原子替换内存快照
            routesSnapshot.set(Collections.unmodifiableMap(newMap));
            log.info("[Gateway] Nacos 动态路由原子更新成功: dataId={}, group={}, 路由数={}",
                    properties.getDataId(), properties.getGroup(), newMap.size());

            // 5. 发布路由刷新事件
            if (publishEvent) {
                publishRefreshEvent();
            }
            return true;
        } catch (Exception ex) {
            log.error("[Gateway] Nacos 动态路由反序列化或语义校验失败，快照守卫生效，坚决保留上一份有效快照: dataId={}, group={}, error={}",
                    properties.getDataId(), properties.getGroup(), ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * 将 JSON 文本反序列化为路由定义列表 (支持 JSON 数组与单 JSON 对象)
     *
     * @param configInfo JSON 文本
     * @return 路由定义列表
     * @throws Exception 反序列化失败异常
     */
    public List<RouteDefinition> parseRouteDefinitions(String configInfo) throws Exception {
        if (!StringUtils.hasText(configInfo)) {
            return Collections.emptyList();
        }
        String trimmed = configInfo.trim();
        if (trimmed.startsWith("[")) {
            return objectMapper.readValue(trimmed, new TypeReference<List<RouteDefinition>>() {});
        } else if (trimmed.startsWith("{")) {
            RouteDefinition single = objectMapper.readValue(trimmed, RouteDefinition.class);
            return single != null ? Collections.singletonList(single) : Collections.emptyList();
        } else {
            throw new IllegalArgumentException("非法的动态路由 JSON 格式: 必须为 JSON 数组或单 JSON 对象");
        }
    }

    /**
     * 对动态路由列表执行深度语法与语义防卫校验 (统一委派至 {@link GatewayRouteValidator})
     *
     * @param routeDefinitions 路由定义列表
     */
    public void validateRouteDefinitions(List<RouteDefinition> routeDefinitions) {
        GatewayRouteValidator.validateRouteDefinitions(routeDefinitions);
    }

    /**
     * 对单条动态路由执行深度语法与语义防卫校验 (统一委派至 {@link GatewayRouteValidator})
     *
     * @param route 路由定义
     */
    public void validateRouteDefinition(RouteDefinition route) {
        GatewayRouteValidator.validateRouteDefinition(route);
    }

    /**
     * 将当前最新内存快照反向持久化写入 Nacos 配置中心
     */
    private void persistSnapshotToNacos() {
        if (configService != null && properties.isEnabled()) {
            try {
                List<RouteDefinition> currentRoutes = new ArrayList<>(routesSnapshot.get().values());
                String json = objectMapper.writeValueAsString(currentRoutes);
                configService.publishConfig(
                        properties.getDataId(),
                        properties.getGroup(),
                        json,
                        ConfigType.JSON.getType()
                );
                log.info("[Gateway] 动态路由反向持久化写入 Nacos 成功: dataId={}, group={}, 路由数={}",
                        properties.getDataId(), properties.getGroup(), currentRoutes.size());
            } catch (Exception ex) {
                log.warn("[Gateway] 动态路由反向持久化写入 Nacos 异常: dataId={}, group={}, error={}",
                        properties.getDataId(), properties.getGroup(), ex.getMessage());
            }
        }
    }

    /**
     * 获取当前不可变内存路由快照映射
     *
     * @return 路由映射 (不可修改)
     */
    public Map<String, RouteDefinition> getRoutesSnapshot() {
        return routesSnapshot.get();
    }

    private void publishRefreshEvent() {
        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(new RefreshRoutesEvent(this));
            log.debug("[Gateway] 已发布 RefreshRoutesEvent 路由刷新事件");
        }
    }
}
