package io.github.atengk.cloud.sentinel.datasource;

import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelNacosRuleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Sentinel Nacos 5 大规则全家桶动态订阅与持久化聚合管理器
 * <p>
 * 基于约定的命名规范，一次性自动建立并监听 Flow、Degrade、ParamFlow、System、Authority 5 种规则数据源，
 * 并将其 SentinelProperty 桥接注册至对应的 RuleManager。
 * 遵循 {@code Rule Fail-Safe Invariant} 防暴毙容错防线与 Nacos 连接参数四级平滑自适应继承。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SentinelNacosRuleSubscriber implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SentinelNacosRuleSubscriber.class);

    private final AtengSentinelNacosRuleProperties properties;
    private final NacosDataSourceFactory dataSourceFactory;
    private final ObjectProvider<ObjectMapper> objectMapperProvider;
    private final String defaultAppName;
    private final Environment environment;

    private final List<ReadableDataSource<?, ?>> dataSources = new CopyOnWriteArrayList<>();
    private final AtomicLong failSafeCount = new AtomicLong(0);
    private final AtomicReference<String> lastFailSafeError = new AtomicReference<>();
    private String resolvedDataIdPrefix;

    public SentinelNacosRuleSubscriber(
            AtengSentinelNacosRuleProperties properties,
            NacosDataSourceFactory dataSourceFactory,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            String defaultAppName) {
        this(properties, dataSourceFactory, objectMapperProvider, defaultAppName, null);
    }

    public SentinelNacosRuleSubscriber(
            AtengSentinelNacosRuleProperties properties,
            NacosDataSourceFactory dataSourceFactory,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            String defaultAppName,
            Environment environment) {
        this.properties = properties;
        this.dataSourceFactory = dataSourceFactory;
        this.objectMapperProvider = objectMapperProvider;
        this.defaultAppName = defaultAppName;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            log.debug("Sentinel Nacos rule subscriber is disabled.");
            return;
        }

        // 1. 确定 DataId 前缀契约与四级自适应继承的 Nacos 连接参数
        this.resolvedDataIdPrefix = resolvePrefix();
        Properties nacosProps = properties.toNacosProperties(environment);
        ObjectMapper objectMapper = resolveObjectMapper();

        // 2. 依次注册 5 大规则数据源
        registerRuleDataSource(properties.getFlow().isEnabled(), properties.getFlow().getDataIdSuffix(),
                nacosProps, objectMapper, new TypeReference<List<FlowRule>>() {}, "FlowRule", FlowRuleManager::register2Property);
        registerRuleDataSource(properties.getDegrade().isEnabled(), properties.getDegrade().getDataIdSuffix(),
                nacosProps, objectMapper, new TypeReference<List<DegradeRule>>() {}, "DegradeRule", DegradeRuleManager::register2Property);
        registerRuleDataSource(properties.getParamFlow().isEnabled(), properties.getParamFlow().getDataIdSuffix(),
                nacosProps, objectMapper, new TypeReference<List<ParamFlowRule>>() {}, "ParamFlowRule", ParamFlowRuleManager::register2Property);
        registerRuleDataSource(properties.getSystem().isEnabled(), properties.getSystem().getDataIdSuffix(),
                nacosProps, objectMapper, new TypeReference<List<SystemRule>>() {}, "SystemRule", SystemRuleManager::register2Property);
        registerRuleDataSource(properties.getAuthority().isEnabled(), properties.getAuthority().getDataIdSuffix(),
                nacosProps, objectMapper, new TypeReference<List<AuthorityRule>>() {}, "AuthorityRule", AuthorityRuleManager::register2Property);

        log.info("Sentinel Nacos 5-spectrum rules successfully subscribed. prefix: [{}], groupId: [{}], total dataSources: [{}]",
                resolvedDataIdPrefix, properties.getGroupId(), dataSources.size());
    }

    @Override
    public void destroy() {
        // 资源确定性释放防线
        for (ReadableDataSource<?, ?> dataSource : dataSources) {
            try {
                if (dataSource instanceof AutoCloseable closeable) {
                    closeable.close();
                }
            } catch (Exception e) {
                log.warn("Error occurred when closing Sentinel Nacos data source: {}", dataSource, e);
            }
        }
        dataSources.clear();
        log.info("Sentinel Nacos rule subscriber destroyed and resources released.");
    }

    public List<ReadableDataSource<?, ?>> getDataSources() {
        return Collections.unmodifiableList(dataSources);
    }

    public String getResolvedDataIdPrefix() {
        return resolvedDataIdPrefix;
    }

    public AtengSentinelNacosRuleProperties getProperties() {
        return properties;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public String getEffectiveServerAddr() {
        return properties.resolveServerAddr(environment);
    }

    public String getEffectiveNamespace() {
        return properties.resolveNamespace(environment);
    }

    /**
     * 获取累计触发 Rule Fail-Safe 防暴毙拦截的次数
     */
    public long getFailSafeCount() {
        return failSafeCount.get();
    }

    /**
     * 获取最近一次触发 Rule Fail-Safe 拦截的错误摘要快照
     */
    public String getLastFailSafeError() {
        return lastFailSafeError.get();
    }

    /**
     * 重置 Rule Fail-Safe 拦截统计状态 (常用于单元测试隔离重置)
     */
    public void resetFailSafeStats() {
        failSafeCount.set(0);
        lastFailSafeError.set(null);
    }

    void recordFailSafe(String ruleTypeName, String detail) {
        failSafeCount.incrementAndGet();
        String safeRule = ruleTypeName != null ? ruleTypeName : "Rule";
        String safeDetail = (detail != null && !detail.trim().isEmpty()) ? detail.trim() : "unknown error";
        String summary = safeRule + ": " + safeDetail;
        if (summary.length() > 512) {
            summary = summary.substring(0, 512) + "...";
        }
        lastFailSafeError.set(summary);
    }

    private String resolvePrefix() {
        if (properties.getDataIdPrefix() != null && !properties.getDataIdPrefix().trim().isEmpty()) {
            return properties.getDataIdPrefix().trim();
        }
        if (defaultAppName != null && !defaultAppName.trim().isEmpty()) {
            return defaultAppName.trim();
        }
        if (environment != null) {
            String appName = environment.getProperty("spring.application.name");
            if (appName != null && !appName.trim().isEmpty()) {
                return appName.trim();
            }
        }
        return "sentinel";
    }

    private ObjectMapper resolveObjectMapper() {
        return objectMapperProvider.getIfAvailable(ObjectMapper::new);
    }

    private <T> void registerRuleDataSource(
            boolean enabled,
            String dataIdSuffix,
            Properties nacosProps,
            ObjectMapper mapper,
            TypeReference<List<T>> typeReference,
            String ruleTypeName,
            Consumer<SentinelProperty<List<T>>> registerAction) {
        if (!enabled) {
            return;
        }
        String dataId = resolvedDataIdPrefix + dataIdSuffix;
        RuleListConverter<T> converter = new RuleListConverter<>(
                mapper, typeReference, ruleTypeName, this::recordFailSafe);
        ReadableDataSource<String, List<T>> dataSource = dataSourceFactory.createDataSource(
                nacosProps, properties.getGroupId(), dataId, converter);
        if (dataSource != null && dataSource.getProperty() != null) {
            registerAction.accept(dataSource.getProperty());
            dataSources.add(dataSource);
            log.debug("Subscribed Sentinel {} from Nacos dataId: {}", ruleTypeName, dataId);
        }
    }

    /**
     * 通用强类型规则反序列化转换器 (带语法与语义双重 Rule Fail-Safe 防暴毙容错防卫)
     *
     * @param <T> 规则类型
     */
    public static class RuleListConverter<T> implements Converter<String, List<T>> {

        private final ObjectMapper objectMapper;
        private final TypeReference<List<T>> typeReference;
        private final String ruleTypeName;
        private final BiConsumer<String, String> failSafeRecorder;

        /**
         * 构造规则反序列化转换器
         *
         * @param objectMapper JSON 序列化器
         * @param typeReference 目标规则列表类型引用
         * @param ruleTypeName 规则类型名称
         */
        public RuleListConverter(ObjectMapper objectMapper, TypeReference<List<T>> typeReference, String ruleTypeName) {
            this(objectMapper, typeReference, ruleTypeName, null);
        }

        /**
         * 构造带 Fail-Safe 回调通知的规则反序列化转换器
         *
         * @param objectMapper JSON 序列化器
         * @param typeReference 目标规则列表类型引用
         * @param ruleTypeName 规则类型名称
         * @param failSafeRecorder Fail-Safe 触发回调 (可为空)
         */
        public RuleListConverter(
                ObjectMapper objectMapper,
                TypeReference<List<T>> typeReference,
                String ruleTypeName,
                BiConsumer<String, String> failSafeRecorder) {
            this.objectMapper = (objectMapper != null ? objectMapper : new ObjectMapper())
                    .copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            this.typeReference = Objects.requireNonNull(typeReference, "typeReference must not be null");
            this.ruleTypeName = ruleTypeName;
            this.failSafeRecorder = failSafeRecorder;
        }

        @Override
        public List<T> convert(String source) {
            if (source == null || source.trim().isEmpty()) {
                return Collections.emptyList();
            }
            try {
                List<T> rules = objectMapper.readValue(source, typeReference);
                if (rules == null || rules.isEmpty()) {
                    return Collections.emptyList();
                }
                validateSemanticRules(rules);
                return rules;
            } catch (IllegalArgumentException e) {
                notifyFailSafe(e.getMessage());
                throw e;
            } catch (Exception e) {
                notifyFailSafe("JSON syntax error: " + e.getMessage());
                throw new IllegalArgumentException("Failed to parse Sentinel " + ruleTypeName + " from JSON payload", e);
            }
        }

        private void validateSemanticRules(List<T> rules) {
            for (int i = 0; i < rules.size(); i++) {
                T rule = rules.get(i);
                if (rule == null) {
                    throw new IllegalArgumentException(
                            "Sentinel " + ruleTypeName + " semantic validation failed at index " + i + ": rule element must not be null");
                }
                if (rule instanceof FlowRule flowRule) {
                    requireNonBlankResource(flowRule.getResource(), i);
                    requireNonNegativeCount(flowRule.getCount(), i);
                } else if (rule instanceof DegradeRule degradeRule) {
                    requireNonBlankResource(degradeRule.getResource(), i);
                    requireNonNegativeCount(degradeRule.getCount(), i);
                } else if (rule instanceof ParamFlowRule paramFlowRule) {
                    requireNonBlankResource(paramFlowRule.getResource(), i);
                    requireNonNegativeCount(paramFlowRule.getCount(), i);
                } else if (rule instanceof AuthorityRule authorityRule) {
                    requireNonBlankResource(authorityRule.getResource(), i);
                }
            }
        }

        private void requireNonBlankResource(String resource, int index) {
            if (resource == null || resource.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Sentinel " + ruleTypeName + " semantic validation failed at index " + index + ": resource must not be blank");
            }
        }

        private void requireNonNegativeCount(double count, int index) {
            if (!Double.isFinite(count) || count < 0.0d) {
                throw new IllegalArgumentException(
                        "Sentinel " + ruleTypeName + " semantic validation failed at index " + index + ": count must be finite and >= 0, actual=" + count);
            }
        }

        private void notifyFailSafe(String detail) {
            if (failSafeRecorder != null) {
                failSafeRecorder.accept(ruleTypeName, detail);
            }
        }
    }
}
