package io.github.atengk.cloud.sentinel.datasource;

import com.alibaba.csp.sentinel.datasource.AbstractDataSource;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
import com.alibaba.csp.sentinel.property.DynamicSentinelProperty;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelNacosRuleProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sentinel Nacos 规则全家桶动态订阅器单元测试 (100% 离线自洽)
 *
 * @author Ateng
 * @since 2026-10-29
 */
class SentinelNacosRuleSubscriberTest {

    @AfterEach
    void tearDown() {
        // 上下文零污染铁律：重置各 RuleManager 内存规则
        FlowRuleManager.loadRules(Collections.emptyList());
        DegradeRuleManager.loadRules(Collections.emptyList());
        ParamFlowRuleManager.loadRules(Collections.emptyList());
        SystemRuleManager.loadRules(Collections.emptyList());
        AuthorityRuleManager.loadRules(Collections.emptyList());
    }

    @Test
    @DisplayName("测试禁用状态下不执行任何订阅注册")
    void shouldNotSubscribeWhenDisabled() {
        AtengSentinelNacosRuleProperties properties = new AtengSentinelNacosRuleProperties();
        properties.setEnabled(false);

        List<String> createdDataIds = new ArrayList<>();
        NacosDataSourceFactory factory = new NacosDataSourceFactory() {
            @Override
            public <T> ReadableDataSource<String, T> createDataSource(
                    Properties props, String groupId, String dataId, Converter<String, T> converter) {
                createdDataIds.add(dataId);
                return new MockReadableDataSource<>(converter);
            }
        };

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("objectMapper", new ObjectMapper()));
        SentinelNacosRuleSubscriber subscriber = new SentinelNacosRuleSubscriber(
                properties, factory, beanFactory.getBeanProvider(ObjectMapper.class), "my-app");

        subscriber.afterPropertiesSet();

        assertThat(createdDataIds).isEmpty();
        assertThat(subscriber.getDataSources()).isEmpty();
    }

    @Test
    @DisplayName("测试开启时约定生成 5 大规则 DataId 并正确注册动态推送")
    void shouldSubscribeAllFiveRulesWithConventionDataIds() {
        AtengSentinelNacosRuleProperties properties = new AtengSentinelNacosRuleProperties();
        properties.setEnabled(true);
        properties.setDataIdPrefix("user-service");
        properties.setGroupId("CUSTOM_GROUP");

        Map<String, MockReadableDataSource<?>> dataSourceMap = new ConcurrentHashMap<>();
        NacosDataSourceFactory factory = new NacosDataSourceFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ReadableDataSource<String, T> createDataSource(
                    Properties props, String groupId, String dataId, Converter<String, T> converter) {
                MockReadableDataSource<T> ds = new MockReadableDataSource<>(converter);
                dataSourceMap.put(dataId, ds);
                return ds;
            }
        };

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("objectMapper", new ObjectMapper()));
        SentinelNacosRuleSubscriber subscriber = new SentinelNacosRuleSubscriber(
                properties, factory, beanFactory.getBeanProvider(ObjectMapper.class), "fallback-app");

        subscriber.afterPropertiesSet();

        assertThat(subscriber.getResolvedDataIdPrefix()).isEqualTo("user-service");
        assertThat(subscriber.getDataSources()).hasSize(5);

        // 验证 5 大规则 DataId
        assertThat(dataSourceMap).containsOnlyKeys(
                "user-service-flow.json",
                "user-service-degrade.json",
                "user-service-param-flow.json",
                "user-service-system.json",
                "user-service-authority.json"
        );

        // 1. 模拟推送 FlowRule
        @SuppressWarnings("unchecked")
        MockReadableDataSource<List<FlowRule>> flowDs = (MockReadableDataSource<List<FlowRule>>) dataSourceMap.get("user-service-flow.json");
        String flowJson = """
                [
                    {
                        "resource": "/api/users",
                        "grade": 1,
                        "count": 10.0,
                        "limitApp": "default"
                    }
                ]
                """;
        flowDs.pushValue(flowJson);
        assertThat(FlowRuleManager.getRules()).hasSize(1);
        assertThat(FlowRuleManager.getRules().get(0).getResource()).isEqualTo("/api/users");
        assertThat(FlowRuleManager.getRules().get(0).getCount()).isEqualTo(10.0);

        // 2. 模拟推送 DegradeRule
        @SuppressWarnings("unchecked")
        MockReadableDataSource<List<DegradeRule>> degradeDs = (MockReadableDataSource<List<DegradeRule>>) dataSourceMap.get("user-service-degrade.json");
        String degradeJson = """
                [
                    {
                        "resource": "/api/users/orders",
                        "grade": 0,
                        "count": 500.0,
                        "timeWindow": 10
                    }
                ]
                """;
        degradeDs.pushValue(degradeJson);
        assertThat(DegradeRuleManager.getRules()).hasSize(1);
        assertThat(DegradeRuleManager.getRules().get(0).getResource()).isEqualTo("/api/users/orders");

        // 3. 模拟推送 ParamFlowRule
        @SuppressWarnings("unchecked")
        MockReadableDataSource<List<ParamFlowRule>> paramFlowDs = (MockReadableDataSource<List<ParamFlowRule>>) dataSourceMap.get("user-service-param-flow.json");
        String paramFlowJson = """
                [
                    {
                        "resource": "getUserDetail",
                        "grade": 1,
                        "paramIdx": 0,
                        "count": 5.0
                    }
                ]
                """;
        paramFlowDs.pushValue(paramFlowJson);
        assertThat(ParamFlowRuleManager.getRules()).hasSize(1);
        assertThat(ParamFlowRuleManager.getRules().get(0).getResource()).isEqualTo("getUserDetail");
        assertThat(ParamFlowRuleManager.getRules().get(0).getParamIdx()).isEqualTo(0);

        // 4. 模拟推送 SystemRule
        @SuppressWarnings("unchecked")
        MockReadableDataSource<List<SystemRule>> systemDs = (MockReadableDataSource<List<SystemRule>>) dataSourceMap.get("user-service-system.json");
        String systemJson = """
                [
                    {
                        "highestSystemLoad": 4.0
                    }
                ]
                """;
        systemDs.pushValue(systemJson);
        assertThat(SystemRuleManager.getRules()).hasSize(1);
        assertThat(SystemRuleManager.getRules().get(0).getHighestSystemLoad()).isEqualTo(4.0);

        // 5. 模拟推送 AuthorityRule
        @SuppressWarnings("unchecked")
        MockReadableDataSource<List<AuthorityRule>> authorityDs = (MockReadableDataSource<List<AuthorityRule>>) dataSourceMap.get("user-service-authority.json");
        String authorityJson = """
                [
                    {
                        "resource": "/admin/dashboard",
                        "limitApp": "order-service,payment-service",
                        "strategy": 0
                    }
                ]
                """;
        authorityDs.pushValue(authorityJson);
        assertThat(AuthorityRuleManager.getRules()).hasSize(1);
        assertThat(AuthorityRuleManager.getRules().get(0).getResource()).isEqualTo("/admin/dashboard");
        assertThat(AuthorityRuleManager.getRules().get(0).getStrategy()).isEqualTo(RuleConstant.AUTHORITY_WHITE);

        // 6. 测试 destroy 释放资源
        subscriber.destroy();
        assertThat(subscriber.getDataSources()).isEmpty();
        dataSourceMap.values().forEach(ds -> assertThat(ds.isClosed()).isTrue());
    }

    @Test
    @DisplayName("测试禁用某子项规则时该规则不被注册")
    void shouldSkipDisabledRuleSubtypes() {
        AtengSentinelNacosRuleProperties properties = new AtengSentinelNacosRuleProperties();
        properties.setEnabled(true);
        properties.getAuthority().setEnabled(false);
        properties.getSystem().setEnabled(false);

        List<String> registeredDataIds = new ArrayList<>();
        NacosDataSourceFactory factory = new NacosDataSourceFactory() {
            @Override
            public <T> ReadableDataSource<String, T> createDataSource(
                    Properties props, String groupId, String dataId, Converter<String, T> converter) {
                registeredDataIds.add(dataId);
                return new MockReadableDataSource<>(converter);
            }
        };

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("objectMapper", new ObjectMapper()));
        SentinelNacosRuleSubscriber subscriber = new SentinelNacosRuleSubscriber(
                properties, factory, beanFactory.getBeanProvider(ObjectMapper.class), "test-app");

        subscriber.afterPropertiesSet();

        assertThat(registeredDataIds).containsExactly(
                "test-app-flow.json",
                "test-app-degrade.json",
                "test-app-param-flow.json"
        );
        assertThat(subscriber.getDataSources()).hasSize(3);
    }

    @Test
    @DisplayName("测试 RuleListConverter 容错防护与空安全保底")
    void testRuleListConverterDefensive() {
        ObjectMapper mapper = new ObjectMapper();
        SentinelNacosRuleSubscriber.RuleListConverter<FlowRule> converter =
                new SentinelNacosRuleSubscriber.RuleListConverter<>(mapper, new TypeReference<>() {}, "FlowRule");

        // 空入参保底返回空列表
        assertThat(converter.convert(null)).isEmpty();
        assertThat(converter.convert("   ")).isEmpty();

        // 格式非法 JSON 优雅捕获返回空列表，不抛异常中断
        assertThat(converter.convert("{invalid-json}")).isEmpty();

        // 包含未知字段的合法 JSON 能平滑反序列化
        String jsonWithUnknownFields = """
                [
                    {
                        "resource": "/test",
                        "count": 5.0,
                        "unknownField": "unexpected-value"
                    }
                ]
                """;
        List<FlowRule> result = converter.convert(jsonWithUnknownFields);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getResource()).isEqualTo("/test");
    }

    /**
     * 内存 Mock 可读数据源
     */
    static class MockReadableDataSource<T> extends AbstractDataSource<String, T> implements AutoCloseable {

        private final Converter<String, T> converter;
        private final DynamicSentinelProperty<T> property = new DynamicSentinelProperty<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        public MockReadableDataSource(Converter<String, T> parser) {
            super(parser);
            this.converter = parser;
        }

        @Override
        public String readSource() {
            return "";
        }

        @Override
        public SentinelProperty<T> getProperty() {
            return property;
        }

        public void pushValue(String source) {
            T value = converter.convert(source);
            property.updateValue(value);
        }

        @Override
        public void close() {
            closed.set(true);
        }

        public boolean isClosed() {
            return closed.get();
        }
    }
}
