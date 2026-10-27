package io.github.atengk.cloud.nacos.refresh;

import com.alibaba.cloud.nacos.refresh.NacosConfigRefreshEvent;
import io.github.atengk.cloud.nacos.event.ConfigChangeItem;
import io.github.atengk.cloud.nacos.event.ConfigChangeType;
import io.github.atengk.cloud.nacos.event.SafeConfigRefreshEvent;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.spi.NacosConfigChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos 安全配置热更核心引擎
 *
 * <p>核心契约与职责：
 * 1. <b>单例状态不可变铁律</b>：热更时严格收敛于 {@code @ConfigurationProperties} 纯属性 POJO 增量重绑，
 *    坚决杜绝通过 {@code @RefreshScope} 对业务 Bean 触发物理销毁与重建；
 * 2. <b>精准变更对比</b>：维护配置属性内存快照，精确计算变更属性项的新旧值对比与变更类型（新增/修改/删除）；
 * 3. <b>MDC 链路穿透</b>：自动在热更回调线程注入标准 {@code traceId}，并在 {@code finally} 中安全清理，消除线程池污染；
 * 4. <b>强隔离异常捕获屏障</b>：逐个调用 {@link NacosConfigChangeListener} SPI，单个监听器异常被严格隔离捕获并记录日志，
 *    绝不阻断后续监听器与事件广播；
 * 5. <b>双模通知机制</b>：同时支持前缀过滤受管 SPI 与 Spring 原生强类型事件（{@link SafeConfigRefreshEvent}）。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class NacosConfigRefreshEngine implements SmartInitializingSingleton, ApplicationListener<ApplicationEvent> {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigRefreshEngine.class);

    public static final String MDC_TRACE_ID_KEY = "traceId";

    private final Environment environment;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<List<NacosConfigChangeListener>> listenersProvider;
    private final ObjectProvider<ConfigurationPropertiesRebinder> rebinderProvider;
    private final AtengNacosProperties properties;

    /**
     * 属性快照缓存（配置属性键 -> 当前环境值），用于增量热更时精确对比 oldValue 与 newValue
     */
    private final Map<String, Object> propertySnapshot = new ConcurrentHashMap<>();

    /**
     * 最近一次触发热更的 Nacos 数据源上下文
     */
    private volatile String lastDataId = "";
    private volatile String lastGroup = "";

    public NacosConfigRefreshEngine(Environment environment,
                                    ApplicationEventPublisher eventPublisher,
                                    ObjectProvider<List<NacosConfigChangeListener>> listenersProvider,
                                    ObjectProvider<ConfigurationPropertiesRebinder> rebinderProvider,
                                    AtengNacosProperties properties) {
        this.environment = environment;
        this.eventPublisher = eventPublisher;
        this.listenersProvider = listenersProvider;
        this.rebinderProvider = rebinderProvider;
        this.properties = properties;
    }

    public NacosConfigRefreshEngine(Environment environment,
                                    ApplicationEventPublisher eventPublisher,
                                    List<NacosConfigChangeListener> listeners,
                                    ConfigurationPropertiesRebinder rebinder,
                                    AtengNacosProperties properties) {
        this(environment, eventPublisher, ofNullable(listeners), ofNullable(rebinder), properties);
    }

    private static <T> ObjectProvider<T> ofNullable(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 初始化环境属性内存快照基线
        capturePropertySnapshot();
        log.debug("[ateng-cloud-nacos] 配置热更引擎基线属性快照初始化完成，已缓存 {} 个配置键", propertySnapshot.size());
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (properties == null || !properties.isEnabled()
                || properties.getConfig() == null || !properties.getConfig().isEnabled()) {
            return;
        }

        if (event instanceof NacosConfigRefreshEvent nacosEvent) {
            this.lastDataId = nacosEvent.getDataId();
            this.lastGroup = nacosEvent.getGroup();
            log.debug("[ateng-cloud-nacos] 捕获 NacosConfigRefreshEvent [DataId: {}, Group: {}]",
                    this.lastDataId, this.lastGroup);
        } else if (event instanceof EnvironmentChangeEvent envChangeEvent) {
            Set<String> changedKeys = envChangeEvent.getKeys();
            if (changedKeys != null && !changedKeys.isEmpty()) {
                refreshConfig(changedKeys, this.lastDataId, this.lastGroup);
            }
        }
    }

    /**
     * 触发针对指定属性键集合的安全配置热更新
     *
     * @param changedKeys 发生变更的属性键名集合
     * @return 发布的强类型安全配置刷新事件
     */
    public SafeConfigRefreshEvent refreshConfig(Set<String> changedKeys) {
        return refreshConfig(changedKeys, null, null);
    }

    /**
     * 触发针对指定属性键集合的安全配置热更新，并关联 Nacos Data ID 与 Group
     *
     * @param changedKeys 发生变更的属性键名集合
     * @param dataId      Nacos 配置集唯一标识
     * @param group       Nacos 配置分组名
     * @return 发布的强类型安全配置刷新事件
     */
    public SafeConfigRefreshEvent refreshConfig(Set<String> changedKeys, String dataId, String group) {
        if (changedKeys == null || changedKeys.isEmpty()) {
            return new SafeConfigRefreshEvent(this, dataId, group, Collections.emptySet(), Collections.emptyMap(), "");
        }

        // 1. 确保 @ConfigurationProperties 纯属性 POJO 增量热绑
        rebindConfigurationProperties();

        // 2. 注入或维持 MDC 链路追踪上下文
        String existingTraceId = MDC.get(MDC_TRACE_ID_KEY);
        String traceId = StringUtils.hasText(existingTraceId) ? existingTraceId : generateTraceId();
        boolean traceIdInjected = false;

        try {
            if (!StringUtils.hasText(existingTraceId)) {
                MDC.put(MDC_TRACE_ID_KEY, traceId);
                traceIdInjected = true;
            }

            // 3. 计算配置项详细变更对比 (oldValue -> newValue)
            Map<String, ConfigChangeItem> changeDetails = computeChangeDetails(changedKeys);

            // 4. 构建强类型事件
            SafeConfigRefreshEvent event = new SafeConfigRefreshEvent(
                    this, dataId, group, changedKeys, changeDetails, traceId);

            log.info("[ateng-cloud-nacos] 开始广播配置安全热更 [DataId: '{}', Group: '{}', TraceId: '{}', ChangedCount: {}]",
                    dataId != null ? dataId : "-", group != null ? group : "-", traceId, changedKeys.size());

            // 5. 调度 NacosConfigChangeListener SPI 监听器（构筑强隔离异常捕获屏障）
            dispatchToListeners(event);

            // 6. 发布 Spring 原生强类型事件，支持 @EventListener 响应
            publishSafeEvent(event);

            log.info("[ateng-cloud-nacos] 配置安全热更新执行完成 [TraceId: '{}', ChangedKeys: {}]", traceId, changedKeys);
            return event;
        } finally {
            if (traceIdInjected) {
                MDC.remove(MDC_TRACE_ID_KEY);
            } else if (existingTraceId != null) {
                MDC.put(MDC_TRACE_ID_KEY, existingTraceId);
            }
        }
    }

    /**
     * 触发 @ConfigurationProperties 增量重绑
     */
    private void rebindConfigurationProperties() {
        if (rebinderProvider == null) {
            return;
        }
        ConfigurationPropertiesRebinder rebinder = rebinderProvider.getIfAvailable();
        if (rebinder != null) {
            try {
                rebinder.rebind();
                log.debug("[ateng-cloud-nacos] ConfigurationPropertiesRebinder 已完成所有 @ConfigurationProperties 属性增量重绑");
            } catch (Exception ex) {
                log.warn("[ateng-cloud-nacos] 执行 ConfigurationPropertiesRebinder.rebind() 时捕获异常: {}", ex.getMessage());
            }
        }
    }

    /**
     * 精确计算新旧值对比
     */
    private Map<String, ConfigChangeItem> computeChangeDetails(Set<String> changedKeys) {
        Map<String, ConfigChangeItem> details = new LinkedHashMap<>();
        for (String key : changedKeys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            Object oldValue = propertySnapshot.get(key);
            Object newValue = environment != null ? environment.getProperty(key) : null;

            ConfigChangeType changeType;
            if (oldValue == null && newValue != null) {
                changeType = ConfigChangeType.ADDED;
            } else if (oldValue != null && newValue == null) {
                changeType = ConfigChangeType.DELETED;
            } else {
                changeType = ConfigChangeType.MODIFIED;
            }

            details.put(key, new ConfigChangeItem(key, oldValue, newValue, changeType));

            // 更新内存快照
            if (newValue != null) {
                propertySnapshot.put(key, newValue);
            } else {
                propertySnapshot.remove(key);
            }
        }
        return details;
    }

    /**
     * 调度 SPI 监听器并构筑异常隔离屏障
     */
    private void dispatchToListeners(SafeConfigRefreshEvent event) {
        if (listenersProvider == null) {
            return;
        }
        List<NacosConfigChangeListener> listeners = listenersProvider.getIfAvailable();
        if (listeners == null || listeners.isEmpty()) {
            return;
        }

        List<NacosConfigChangeListener> sortedListeners = new ArrayList<>(listeners);
        sortedListeners.sort((l1, l2) -> {
            int o1 = l1 != null ? l1.getOrder() : 0;
            int o2 = l2 != null ? l2.getOrder() : 0;
            return Integer.compare(o1, o2);
        });

        for (NacosConfigChangeListener listener : sortedListeners) {
            if (listener == null) {
                continue;
            }
            String prefix = listener.getPrefix();
            if (event.containsPrefix(prefix)) {
                try {
                    listener.onConfigChange(event);
                } catch (Throwable t) {
                    log.error("[ateng-cloud-nacos] 配置热更监听器执行异常 [Listener: {}, Prefix: '{}'], 已隔离拦截: {}",
                            listener.getClass().getName(), prefix, t.getMessage(), t);
                }
            } else {
                log.trace("[ateng-cloud-nacos] 监听器 '{}' 前缀 '{}' 与变更集无交集，跳过调用",
                        listener.getClass().getSimpleName(), prefix);
            }
        }
    }

    /**
     * 发布 Spring 强类型事件
     */
    private void publishSafeEvent(SafeConfigRefreshEvent event) {
        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(event);
        } catch (Throwable t) {
            log.error("[ateng-cloud-nacos] 发布 SafeConfigRefreshEvent 事件时捕获异常: {}", t.getMessage(), t);
        }
    }

    /**
     * 捕获当前环境的所有可枚举属性快照
     */
    public void capturePropertySnapshot() {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return;
        }
        for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
            if (propertySource instanceof EnumerablePropertySource<?> enumerableSource) {
                for (String name : enumerableSource.getPropertyNames()) {
                    if (name != null && !propertySnapshot.containsKey(name)) {
                        Object val = environment.getProperty(name);
                        if (val != null) {
                            propertySnapshot.put(name, val);
                        }
                    }
                }
            }
        }
    }

    /**
     * 获取当前属性快照（只读视图）
     */
    public Map<String, Object> getPropertySnapshot() {
        return Collections.unmodifiableMap(propertySnapshot);
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
