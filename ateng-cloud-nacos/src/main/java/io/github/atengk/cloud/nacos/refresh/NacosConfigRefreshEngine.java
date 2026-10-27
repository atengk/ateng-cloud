package io.github.atengk.cloud.nacos.refresh;

import com.alibaba.cloud.nacos.refresh.NacosConfigRefreshEvent;
import io.github.atengk.cloud.nacos.event.ConfigChangeItem;
import io.github.atengk.cloud.nacos.event.ConfigChangeType;
import io.github.atengk.cloud.nacos.event.SafeConfigRefreshEvent;
import io.github.atengk.cloud.nacos.metrics.NacosMetricsManager;
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
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos 现代配置中心安全动态热更核心引擎
 *
 * <p>微服务专属架构铁律落地：
 * <ul>
 *     <li><b>单例 Bean 状态绝对不可变</b>：严禁在 {@code @Service}/{@code @Repository} 等业务单例上标注 {@code @RefreshScope}，
 *     热更新仅增量重绑 {@code @ConfigurationProperties} 纯属性 POJO；</li>
 *     <li><b>受管监听 SPI 过滤匹配</b>：支持注册 {@link NacosConfigChangeListener}，按配置前缀精准过滤与顺序执行；</li>
 *     <li><b>强类型安全事件通知</b>：发布 {@link SafeConfigRefreshEvent} 强类型事件，携带脱敏变更明细；</li>
 *     <li><b>MDC 链路追踪穿透</b>：自动在热更全链路注入并传递 {@code traceId}，并在 {@code finally} 块安全恢复或清除；</li>
 *     <li><b>异常捕获隔离屏障</b>：单个监听器抛出异常仅记录错误日志，绝不阻断后续监听器与事件广播；</li>
 *     <li><b>Micrometer 全维监控与脱敏防泄露</b>：向监控指标上报热更频次与成功/失败指标，并在审计日志中星号脱敏敏感属性。</li>
 * </ul>
 * </p>
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
    private final ObjectProvider<NacosMetricsManager> metricsManagerProvider;

    /**
     * 属性快照缓存（配置属性键 -> 当前环境值），用于增量热更时精确对比 oldValue 与 newValue
     */
    private final Map<String, Object> propertySnapshot = new ConcurrentHashMap<>();

    /**
     * 最近一次触发热更的 Nacos 数据源上下文
     */
    private volatile String lastDataId = "";
    private volatile String lastGroup = "";

    /**
     * 最近一次热更执行状态记录（供 Actuator 运维端点读取展示）
     */
    private volatile RefreshRecord lastRefreshRecord;

    public NacosConfigRefreshEngine(Environment environment,
                                    ApplicationEventPublisher eventPublisher,
                                    ObjectProvider<List<NacosConfigChangeListener>> listenersProvider,
                                    ObjectProvider<ConfigurationPropertiesRebinder> rebinderProvider,
                                    AtengNacosProperties properties) {
        this(environment, eventPublisher, listenersProvider, rebinderProvider, properties, ofNullable(null));
    }

    public NacosConfigRefreshEngine(Environment environment,
                                    ApplicationEventPublisher eventPublisher,
                                    ObjectProvider<List<NacosConfigChangeListener>> listenersProvider,
                                    ObjectProvider<ConfigurationPropertiesRebinder> rebinderProvider,
                                    AtengNacosProperties properties,
                                    ObjectProvider<NacosMetricsManager> metricsManagerProvider) {
        this.environment = environment;
        this.eventPublisher = eventPublisher;
        this.listenersProvider = listenersProvider;
        this.rebinderProvider = rebinderProvider;
        this.properties = properties;
        this.metricsManagerProvider = metricsManagerProvider;
    }

    public NacosConfigRefreshEngine(Environment environment,
                                    ApplicationEventPublisher eventPublisher,
                                    List<NacosConfigChangeListener> listeners,
                                    ConfigurationPropertiesRebinder rebinder,
                                    AtengNacosProperties properties) {
        this(environment, eventPublisher, ofNullable(listeners), ofNullable(rebinder), properties, ofNullable(null));
    }

    public NacosConfigRefreshEngine(Environment environment,
                                    ApplicationEventPublisher eventPublisher,
                                    List<NacosConfigChangeListener> listeners,
                                    ConfigurationPropertiesRebinder rebinder,
                                    AtengNacosProperties properties,
                                    NacosMetricsManager metricsManager) {
        this(environment, eventPublisher, ofNullable(listeners), ofNullable(rebinder), properties, ofNullable(metricsManager));
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
        // 捕获初始配置基线快照
        capturePropertySnapshot();
        log.info("[ateng-cloud-nacos] 初始配置属性快照采集完成，共纳管 {} 个配置项", propertySnapshot.size());
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        // 1. 捕获 Nacos 原生刷新事件以提取 dataId 与 group 上下文
        if (event instanceof NacosConfigRefreshEvent nacosEvent) {
            this.lastDataId = nacosEvent.getDataId();
            this.lastGroup = nacosEvent.getGroup();
            log.debug("[ateng-cloud-nacos] 侦测到 NacosConfigRefreshEvent [DataId: '{}', Group: '{}']",
                    this.lastDataId, this.lastGroup);
            return;
        }

        // 2. 捕获 Spring Cloud 标准 EnvironmentChangeEvent 并驱动安全热更流程
        if (event instanceof EnvironmentChangeEvent envEvent) {
            Set<String> keys = envEvent.getKeys();
            if (keys == null || keys.isEmpty()) {
                return;
            }
            log.info("[ateng-cloud-nacos] 捕获配置变更事件 EnvironmentChangeEvent，受影响键集: {}", keys);
            refreshConfig(keys, this.lastDataId, this.lastGroup);
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

        if (properties != null && properties.getConfig() != null && !properties.getConfig().isEnabled()) {
            log.debug("[ateng-cloud-nacos] Nacos 配置中心治理增强未启用，跳过安全热更执行。");
            return new SafeConfigRefreshEvent(this, dataId, group, Collections.emptySet(), Collections.emptyMap(), "");
        }

        String effectiveGroup = StringUtils.hasText(group) ? group :
                (properties != null && properties.getConfig() != null && StringUtils.hasText(properties.getConfig().getDefaultGroup())
                        ? properties.getConfig().getDefaultGroup() : "DEFAULT_GROUP");

        long startTime = System.currentTimeMillis();
        boolean anyError = false;
        String errorMessage = null;

        // 1. 注入或维持 MDC 链路追踪上下文 (置于最前置，确保重绑与广播全程具备 traceId)
        String existingTraceId = MDC.get(MDC_TRACE_ID_KEY);
        String traceId = StringUtils.hasText(existingTraceId) ? existingTraceId : generateTraceId();
        boolean traceIdInjected = false;

        try {
            if (!StringUtils.hasText(existingTraceId)) {
                MDC.put(MDC_TRACE_ID_KEY, traceId);
                traceIdInjected = true;
            }

            // 2. 确保 @ConfigurationProperties 纯属性 POJO 增量热绑
            rebindConfigurationProperties();

            // 3. 计算配置项详细变更对比 (oldValue -> newValue)
            Map<String, ConfigChangeItem> changeDetails = computeChangeDetails(changedKeys);

            // 4. 构建强类型事件
            SafeConfigRefreshEvent event = new SafeConfigRefreshEvent(
                    this, dataId, effectiveGroup, changedKeys, changeDetails, traceId);

            log.info("[ateng-cloud-nacos] 开始广播配置安全热更 [DataId: '{}', Group: '{}', TraceId: '{}', ChangedCount: {}]",
                    dataId != null ? dataId : "-", effectiveGroup, traceId, changedKeys.size());

            // 审计日志打印（对敏感属性严格执行星号脱敏）
            for (ConfigChangeItem item : changeDetails.values()) {
                log.debug("[ateng-cloud-nacos] 配置变更明细 [TraceId: '{}']: key={}, oldValue={}, newValue={}, type={}",
                        traceId, item.getKey(), item.getMaskedOldValue(), item.getMaskedNewValue(), item.getChangeType());
            }

            // 5. 调度 NacosConfigChangeListener SPI 监听器（构筑强隔离异常捕获屏障）
            boolean listenerErrors = dispatchToListeners(event);
            if (listenerErrors) {
                anyError = true;
                errorMessage = "One or more NacosConfigChangeListener failed";
            }

            // 6. 发布 Spring 原生强类型事件，支持 @EventListener 响应
            boolean eventError = publishSafeEvent(event);
            if (eventError) {
                anyError = true;
                if (errorMessage == null) {
                    errorMessage = "SafeConfigRefreshEvent publication encountered errors";
                }
            }

            log.info("[ateng-cloud-nacos] 配置安全热更新执行完成 [TraceId: '{}', ChangedKeys: {}]", traceId, changedKeys);
            return event;
        } catch (Throwable t) {
            anyError = true;
            errorMessage = t.getMessage();
            throw t;
        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            this.lastRefreshRecord = new RefreshRecord(traceId, System.currentTimeMillis(), changedKeys.size(), changedKeys, !anyError, errorMessage, durationMs);

            // 7. 向 Micrometer 上报监控标尺
            if (metricsManagerProvider != null) {
                NacosMetricsManager metrics = metricsManagerProvider.getIfAvailable();
                if (metrics != null) {
                    if (anyError) {
                        metrics.recordRefreshFailure(durationMs, errorMessage);
                    } else {
                        metrics.recordRefreshSuccess(durationMs, changedKeys.size());
                    }
                }
            }

            // 8. 闭环清理 MDC
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
     *
     * @param event 刷新事件
     * @return 若存在任何监听器执行抛错返回 true，全部成功返回 false
     */
    private boolean dispatchToListeners(SafeConfigRefreshEvent event) {
        List<NacosConfigChangeListener> sortedListeners = getListeners();
        if (sortedListeners.isEmpty()) {
            return false;
        }

        boolean hasError = false;
        for (NacosConfigChangeListener listener : sortedListeners) {
            if (listener == null) {
                continue;
            }
            String prefix = listener.getPrefix();
            if (event.containsPrefix(prefix)) {
                try {
                    listener.onConfigChange(event);
                } catch (Throwable t) {
                    hasError = true;
                    log.error("[ateng-cloud-nacos] 配置热更监听器执行异常 [Listener: {}, Prefix: '{}'], 已隔离拦截: {}",
                            listener.getClass().getName(), prefix, t.getMessage(), t);
                }
            } else {
                log.trace("[ateng-cloud-nacos] 监听器 '{}' 前缀 '{}' 与变更集无交集，跳过调用",
                        listener.getClass().getSimpleName(), prefix);
            }
        }
        return hasError;
    }

    /**
     * 获取按优先级排序的受管监听器列表
     */
    public List<NacosConfigChangeListener> getListeners() {
        if (listenersProvider == null) {
            return Collections.emptyList();
        }
        List<NacosConfigChangeListener> listeners = listenersProvider.getIfAvailable();
        if (listeners == null || listeners.isEmpty()) {
            return Collections.emptyList();
        }

        List<NacosConfigChangeListener> sortedListeners = new ArrayList<>(listeners);
        sortedListeners.sort((l1, l2) -> {
            int o1 = l1 != null ? l1.getOrder() : 0;
            int o2 = l2 != null ? l2.getOrder() : 0;
            return Integer.compare(o1, o2);
        });
        return Collections.unmodifiableList(sortedListeners);
    }

    /**
     * 发布 Spring 强类型事件
     *
     * @param event 待发布的配置安全热更事件
     * @return 是否发生异常（true 表示发布期间发生异常，false 表示正常完成）
     */
    private boolean publishSafeEvent(SafeConfigRefreshEvent event) {
        if (eventPublisher == null) {
            return false;
        }
        try {
            eventPublisher.publishEvent(event);
            return false;
        } catch (Throwable t) {
            log.error("[ateng-cloud-nacos] 发布 SafeConfigRefreshEvent 事件时捕获异常: {}", t.getMessage(), t);
            return true;
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

    /**
     * 获取最近一次热更执行记录
     */
    @Nullable
    public RefreshRecord getLastRefreshRecord() {
        return lastRefreshRecord;
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 最近一次配置热更执行明细记录
     */
    public static class RefreshRecord {

        private final String traceId;
        private final long timestamp;
        private final int changedKeysCount;
        private final Set<String> changedKeys;
        private final boolean success;
        private final String error;
        private final long durationMs;

        public RefreshRecord(String traceId,
                             long timestamp,
                             int changedKeysCount,
                             Set<String> changedKeys,
                             boolean success,
                             @Nullable String error,
                             long durationMs) {
            this.traceId = traceId;
            this.timestamp = timestamp;
            this.changedKeysCount = changedKeysCount;
            this.changedKeys = changedKeys != null ? Collections.unmodifiableSet(changedKeys) : Collections.emptySet();
            this.success = success;
            this.error = error;
            this.durationMs = durationMs;
        }

        public String getTraceId() {
            return traceId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public int getChangedKeysCount() {
            return changedKeysCount;
        }

        public Set<String> getChangedKeys() {
            return changedKeys;
        }

        public boolean isSuccess() {
            return success;
        }

        @Nullable
        public String getError() {
            return error;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }
}
