package io.github.atengk.cloud.sentinel.datasource;

import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

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

    private final List<ReadableDataSource<?, ?>> dataSources = new ArrayList<>();
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
        registerFlowRuleDataSource(nacosProps, objectMapper);
        registerDegradeRuleDataSource(nacosProps, objectMapper);
        registerParamFlowRuleDataSource(nacosProps, objectMapper);
        registerSystemRuleDataSource(nacosProps, objectMapper);
        registerAuthorityRuleDataSource(nacosProps, objectMapper);

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

    private void registerFlowRuleDataSource(Properties nacosProps, ObjectMapper mapper) {
        if (!properties.getFlow().isEnabled()) {
            return;
        }
        String dataId = resolvedDataIdPrefix + properties.getFlow().getDataIdSuffix();
        RuleListConverter<FlowRule> converter = new RuleListConverter<>(mapper, new TypeReference<>() {}, "FlowRule");
        ReadableDataSource<String, List<FlowRule>> dataSource = dataSourceFactory.createDataSource(
                nacosProps, properties.getGroupId(), dataId, converter);
        if (dataSource != null && dataSource.getProperty() != null) {
            FlowRuleManager.register2Property(dataSource.getProperty());
            dataSources.add(dataSource);
            log.debug("Subscribed Sentinel FlowRule from Nacos dataId: {}", dataId);
        }
    }

    private void registerDegradeRuleDataSource(Properties nacosProps, ObjectMapper mapper) {
        if (!properties.getDegrade().isEnabled()) {
            return;
        }
        String dataId = resolvedDataIdPrefix + properties.getDegrade().getDataIdSuffix();
        RuleListConverter<DegradeRule> converter = new RuleListConverter<>(mapper, new TypeReference<>() {}, "DegradeRule");
        ReadableDataSource<String, List<DegradeRule>> dataSource = dataSourceFactory.createDataSource(
                nacosProps, properties.getGroupId(), dataId, converter);
        if (dataSource != null && dataSource.getProperty() != null) {
            DegradeRuleManager.register2Property(dataSource.getProperty());
            dataSources.add(dataSource);
            log.debug("Subscribed Sentinel DegradeRule from Nacos dataId: {}", dataId);
        }
    }

    private void registerParamFlowRuleDataSource(Properties nacosProps, ObjectMapper mapper) {
        if (!properties.getParamFlow().isEnabled()) {
            return;
        }
        String dataId = resolvedDataIdPrefix + properties.getParamFlow().getDataIdSuffix();
        RuleListConverter<ParamFlowRule> converter = new RuleListConverter<>(mapper, new TypeReference<>() {}, "ParamFlowRule");
        ReadableDataSource<String, List<ParamFlowRule>> dataSource = dataSourceFactory.createDataSource(
                nacosProps, properties.getGroupId(), dataId, converter);
        if (dataSource != null && dataSource.getProperty() != null) {
            ParamFlowRuleManager.register2Property(dataSource.getProperty());
            dataSources.add(dataSource);
            log.debug("Subscribed Sentinel ParamFlowRule from Nacos dataId: {}", dataId);
        }
    }

    private void registerSystemRuleDataSource(Properties nacosProps, ObjectMapper mapper) {
        if (!properties.getSystem().isEnabled()) {
            return;
        }
        String dataId = resolvedDataIdPrefix + properties.getSystem().getDataIdSuffix();
        RuleListConverter<SystemRule> converter = new RuleListConverter<>(mapper, new TypeReference<>() {}, "SystemRule");
        ReadableDataSource<String, List<SystemRule>> dataSource = dataSourceFactory.createDataSource(
                nacosProps, properties.getGroupId(), dataId, converter);
        if (dataSource != null && dataSource.getProperty() != null) {
            SystemRuleManager.register2Property(dataSource.getProperty());
            dataSources.add(dataSource);
            log.debug("Subscribed Sentinel SystemRule from Nacos dataId: {}", dataId);
        }
    }

    private void registerAuthorityRuleDataSource(Properties nacosProps, ObjectMapper mapper) {
        if (!properties.getAuthority().isEnabled()) {
            return;
        }
        String dataId = resolvedDataIdPrefix + properties.getAuthority().getDataIdSuffix();
        RuleListConverter<AuthorityRule> converter = new RuleListConverter<>(mapper, new TypeReference<>() {}, "AuthorityRule");
        ReadableDataSource<String, List<AuthorityRule>> dataSource = dataSourceFactory.createDataSource(
                nacosProps, properties.getGroupId(), dataId, converter);
        if (dataSource != null && dataSource.getProperty() != null) {
            AuthorityRuleManager.register2Property(dataSource.getProperty());
            dataSources.add(dataSource);
            log.debug("Subscribed Sentinel AuthorityRule from Nacos dataId: {}", dataId);
        }
    }

    /**
     * 通用强类型规则反序列化转换器 (带 Rule Fail-Safe 防暴毙容错防卫与空安全保底)
     *
     * @param <T> 规则类型
     */
    public static class RuleListConverter<T> implements Converter<String, List<T>> {

        private final ObjectMapper objectMapper;
        private final TypeReference<List<T>> typeReference;
        private final String ruleTypeName;

        public RuleListConverter(ObjectMapper objectMapper, TypeReference<List<T>> typeReference, String ruleTypeName) {
            this.objectMapper = (objectMapper != null ? objectMapper : new ObjectMapper())
                    .copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            this.typeReference = typeReference;
            this.ruleTypeName = ruleTypeName;
        }

        @Override
        public List<T> convert(String source) {
            if (source == null || source.trim().isEmpty()) {
                return Collections.emptyList();
            }
            try {
                List<T> rules = objectMapper.readValue(source, typeReference);
                return rules != null ? rules : Collections.emptyList();
            } catch (Exception e) {
                log.error("CRITICAL: Failed to parse Sentinel {} from JSON due to syntax error! Aborting rule update to preserve Last Known Good Rules. Malformed payload: [{}]",
                        ruleTypeName, source, e);
                throw new IllegalArgumentException("Failed to parse Sentinel " + ruleTypeName + " from JSON payload", e);
            }
        }
    }
}
