package io.github.atengk.cloud.gateway.route;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayDynamicRouteProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 微服务主配置文件 ({@code spring.cloud.gateway.routes}) 动态感知与安全热更监听器
 *
 * <p>与独立路由文件 ({@code gateway-routes.json}) 的 {@link NacosRouteDefinitionRepository} 形成双轨并存的动态热更体系：
 * <ul>
 *     <li><b>环境感知</b>：统一监听 Spring Cloud {@link EnvironmentChangeEvent} 与 Nacos {@code SafeConfigRefreshEvent}，精准匹配 {@code spring.cloud.gateway.routes} 与 {@code default-filters} 前缀；</li>
 *     <li><b>增量热绑</b>：使用 Spring Boot {@link Binder} 动态提取候选路由定义；</li>
 *     <li><b>统一语法语义校验</b>：依托 {@link GatewayRouteValidator} 实施严格的 URI、协议头、断言和过滤器语法防卫；</li>
 *     <li><b>快照守卫容错 (Keep Last-Known Good Snapshot)</b>：若主配置中配置了非法/破损路由，坚决拦截并回退为上一份有效路由快照，杜绝整网瘫痪；</li>
 *     <li><b>幂等防抖刷新</b>：通过路由指纹去重，无实质变更时不重复广播 {@link RefreshRoutesEvent}。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class GatewayRouteRefreshListener implements ApplicationListener<ApplicationEvent>,
        InitializingBean, ApplicationEventPublisherAware {

    private static final Logger log = LoggerFactory.getLogger(GatewayRouteRefreshListener.class);
    private static final String SAFE_CONFIG_REFRESH_EVENT_CLASS =
            "io.github.atengk.cloud.nacos.event.SafeConfigRefreshEvent";

    private final GatewayProperties gatewayProperties;
    private final Environment environment;
    private final AtengGatewayDynamicRouteProperties properties;
    private ApplicationEventPublisher eventPublisher;

    /**
     * 上一份校验通过的有效路由快照原子引用 (Last-Known Good Snapshot)，初始为 null 表示尚未执行基线采集或首次刷新
     */
    private final AtomicReference<RouteSnapshot> lastKnownGoodSnapshot =
            new AtomicReference<>(null);

    public GatewayRouteRefreshListener(
            GatewayProperties gatewayProperties,
            Environment environment,
            @Nullable ApplicationEventPublisher eventPublisher,
            @Nullable AtengGatewayDynamicRouteProperties properties) {
        this.gatewayProperties = gatewayProperties;
        this.environment = environment;
        this.eventPublisher = eventPublisher;
        this.properties = properties != null ? properties : new AtengGatewayDynamicRouteProperties();
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void afterPropertiesSet() {
        if (gatewayProperties != null) {
            List<RouteDefinition> initialRoutes = gatewayProperties.getRoutes() != null
                    ? new ArrayList<>(gatewayProperties.getRoutes())
                    : Collections.emptyList();
            List<FilterDefinition> initialDefaultFilters = gatewayProperties.getDefaultFilters() != null
                    ? new ArrayList<>(gatewayProperties.getDefaultFilters())
                    : Collections.emptyList();
            try {
                GatewayRouteValidator.validateRouteDefinitions(initialRoutes);
                RouteSnapshot snapshot = new RouteSnapshot(initialRoutes, initialDefaultFilters);
                lastKnownGoodSnapshot.set(snapshot);
                log.info("[Gateway] 主配置路由基线快照采集完成，初始路由数: {}, 默认过滤器数: {}",
                        snapshot.routes().size(), snapshot.defaultFilters().size());
            } catch (Exception ex) {
                log.warn("[Gateway] 主配置初始路由存在语法校验警告，安全保留空基线以防快照污染: {}", ex.getMessage());
                lastKnownGoodSnapshot.set(RouteSnapshot.EMPTY);
            }
        }
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event == null) {
            return;
        }

        // 1. 响应 Spring Cloud 标准 EnvironmentChangeEvent
        if (event instanceof EnvironmentChangeEvent envEvent) {
            refreshRoutesFromEnvironment(envEvent.getKeys());
            return;
        }

        // 2. 响应 Nacos SafeConfigRefreshEvent 安全热更事件 (通过反射解耦，避免硬类依赖)
        if (SAFE_CONFIG_REFRESH_EVENT_CLASS.equals(event.getClass().getName())) {
            handleSafeConfigRefreshEvent(event);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleSafeConfigRefreshEvent(ApplicationEvent event) {
        try {
            Method method = event.getClass().getMethod("getChangedKeys");
            Object result = method.invoke(event);
            Set<String> keys = result instanceof Set ? (Set<String>) result : null;
            refreshRoutesFromEnvironment(keys);
        } catch (Exception ex) {
            log.debug("[Gateway] 读取 SafeConfigRefreshEvent.getChangedKeys() 异常，尝试全量环境刷新: {}", ex.getMessage());
            refreshRoutesFromEnvironment(null);
        }
    }

    /**
     * 响应环境变量属性变更集，动态触发主配置路由的安全热更与快照校验
     *
     * @param changedKeys 发生变更的环境变量键集合 (可为 null 或空，表示全量环境刷新)
     * @return 若成功刷新路由或因内容相同无需刷新返回 true；若校验失败回退快照或未匹配路由键返回 false
     */
    public synchronized boolean refreshRoutesFromEnvironment(@Nullable Set<String> changedKeys) {
        if (!properties.isEnabled()) {
            log.debug("[Gateway] 动态路由已关闭 (ateng.cloud.gateway.dynamic-route.enabled=false)，跳过主配置路由热更");
            return false;
        }

        // 1. 判断是否包含路由相关属性变更 (若为 null 或空集合，视为 Spring 整体环境热更)
        if (changedKeys != null && !changedKeys.isEmpty()) {
            boolean hasRouteKey = false;
            for (String key : changedKeys) {
                if (isRouteRelatedKey(key)) {
                    hasRouteKey = true;
                    break;
                }
            }
            if (!hasRouteKey) {
                return false;
            }
            log.info("[Gateway] 捕获主配置路由属性变更通知，受影响键集: {}", changedKeys);
        } else {
            log.info("[Gateway] 捕获全量环境热更通知，准备刷新主配置路由表");
        }

        if (environment == null) {
            log.warn("[Gateway] Spring Environment 为 null，无法执行路由属性增量热绑");
            return false;
        }

        // 2. 动态提取候选路由配置
        GatewayProperties candidateProperties = new GatewayProperties();
        try {
            Binder.get(environment).bind("spring.cloud.gateway", Bindable.ofInstance(candidateProperties));
        } catch (Exception ex) {
            log.error("[Gateway] 主配置路由属性重新绑定异常，快照守卫生效，保留现有有效路由: {}", ex.getMessage(), ex);
            restoreLastKnownGoodRoutes();
            return false;
        }

        List<RouteDefinition> candidateRoutes = candidateProperties.getRoutes() != null
                ? candidateProperties.getRoutes()
                : Collections.emptyList();
        List<FilterDefinition> candidateDefaultFilters = candidateProperties.getDefaultFilters() != null
                ? candidateProperties.getDefaultFilters()
                : Collections.emptyList();

        // 3. 深度语法与语义校验
        try {
            GatewayRouteValidator.validateRouteDefinitions(candidateRoutes);
        } catch (Exception ex) {
            log.error("[Gateway] 主配置路由深度语法或语义校验失败，快照守卫生效，坚决保留上一份有效快照 [{} 条有效路由]: error={}",
                    getLastKnownGoodRoutes().size(), ex.getMessage(), ex);
            // 立即防御性回滚受管 GatewayProperties 实例，防止脏数据对外扩散
            restoreLastKnownGoodRoutes();
            return false;
        }

        // 4. 指纹去重，防御重复事件引发无效刷新
        RouteSnapshot currentSnapshot = lastKnownGoodSnapshot.get();
        if (currentSnapshot != null && currentSnapshot.matches(candidateRoutes, candidateDefaultFilters)) {
            log.debug("[Gateway] 主配置路由与默认过滤器内容未发生实质变更 (fingerprint={})，跳过重复刷新", currentSnapshot.fingerprint());
            return true;
        }

        // 5. 原子替换快照并同步至受管 GatewayProperties
        RouteSnapshot newSnapshot = new RouteSnapshot(candidateRoutes, candidateDefaultFilters);
        lastKnownGoodSnapshot.set(newSnapshot);
        if (gatewayProperties != null) {
            gatewayProperties.setRoutes(new ArrayList<>(candidateRoutes));
            gatewayProperties.setDefaultFilters(new ArrayList<>(candidateDefaultFilters));
        }

        // 6. 发布 RefreshRoutesEvent 广播路由更新
        publishRefreshRoutesEvent();
        log.info("[Gateway] 主配置动态路由原子热更成功，生效路由数: {}, 默认过滤器数: {}, 已触发网关路由表原子刷新",
                candidateRoutes.size(), candidateDefaultFilters.size());
        return true;
    }

    /**
     * 将受管 GatewayProperties 的路由表与默认过滤器回滚为上一份有效快照
     */
    private void restoreLastKnownGoodRoutes() {
        if (gatewayProperties != null) {
            RouteSnapshot snapshot = getLastKnownGoodSnapshot();
            gatewayProperties.setRoutes(new ArrayList<>(snapshot.routes()));
            gatewayProperties.setDefaultFilters(new ArrayList<>(snapshot.defaultFilters()));
            log.warn("[Gateway] 已将受管 GatewayProperties 强制回滚至上一份有效路由快照 [{} 条路由, {} 个默认过滤器]",
                    snapshot.routes().size(), snapshot.defaultFilters().size());
        }
    }

    /**
     * 判断指定属性键是否与 Spring Cloud Gateway 路由或全局默认过滤器相关
     *
     * @param key 属性键名
     * @return 若相关返回 true，否则返回 false
     */
    public static boolean isRouteRelatedKey(@Nullable String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.startsWith(GatewayConstants.PREFIX_GATEWAY_ROUTES)
                || lower.startsWith(GatewayConstants.PREFIX_GATEWAY_DEFAULT_FILTERS)
                || lower.startsWith(GatewayConstants.PREFIX_GATEWAY_DEFAULT_FILTERS_COMPACT);
    }

    private void publishRefreshRoutesEvent() {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new RefreshRoutesEvent(this));
            log.debug("[Gateway] 已发布 RefreshRoutesEvent 路由刷新事件");
        }
    }

    /**
     * 获取当前校验通过的有效路由快照映射列表
     *
     * @return 有效路由快照 (不可修改)
     */
    public List<RouteDefinition> getLastKnownGoodRoutes() {
        RouteSnapshot snapshot = lastKnownGoodSnapshot.get();
        return snapshot != null ? snapshot.routes() : Collections.emptyList();
    }

    /**
     * 获取当前校验通过的有效路由快照不可变对象
     *
     * @return 有效路由不可变快照对象
     */
    public RouteSnapshot getLastKnownGoodSnapshot() {
        RouteSnapshot snapshot = lastKnownGoodSnapshot.get();
        return snapshot != null ? snapshot : RouteSnapshot.EMPTY;
    }

    /**
     * 路由与默认过滤器不可变快照对象
     *
     * @param routes 路由定义不可变列表
     * @param defaultFilters 默认过滤器定义不可变列表
     * @param fingerprint 路由与过滤器联合哈希指纹
     */
    public record RouteSnapshot(
            List<RouteDefinition> routes,
            List<FilterDefinition> defaultFilters,
            int fingerprint
    ) {
        public static final RouteSnapshot EMPTY = new RouteSnapshot(
                Collections.emptyList(),
                Collections.emptyList(),
                Objects.hash(Collections.emptyList(), Collections.emptyList())
        );

        public RouteSnapshot(List<RouteDefinition> routes, List<FilterDefinition> defaultFilters) {
            this(
                    routes != null ? Collections.unmodifiableList(new ArrayList<>(routes)) : Collections.emptyList(),
                    defaultFilters != null ? Collections.unmodifiableList(new ArrayList<>(defaultFilters)) : Collections.emptyList(),
                    Objects.hash(routes != null ? routes : Collections.emptyList(),
                            defaultFilters != null ? defaultFilters : Collections.emptyList())
            );
        }

        public boolean matches(List<RouteDefinition> candidateRoutes, List<FilterDefinition> candidateDefaultFilters) {
            int candidateFingerprint = Objects.hash(
                    candidateRoutes != null ? candidateRoutes : Collections.emptyList(),
                    candidateDefaultFilters != null ? candidateDefaultFilters : Collections.emptyList());
            return this.fingerprint == candidateFingerprint
                    && Objects.equals(this.routes, candidateRoutes)
                    && Objects.equals(this.defaultFilters, candidateDefaultFilters);
        }
    }
}
