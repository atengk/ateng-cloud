package io.github.atengk.cloud.sample.sentinel;

import com.alibaba.csp.sentinel.datasource.AbstractDataSource;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
import com.alibaba.csp.sentinel.property.DynamicSentinelProperty;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.datasource.NacosDataSourceFactory;
import io.github.atengk.cloud.sentinel.datasource.SentinelNacosRuleSubscriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sentinel Nacos 5 大规则全家桶脱网动态推送与端到端热加载自验测试 (100% 离线自洽)
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest(
        classes = SampleSentinelApplication.class,
        properties = {
                "ateng.cloud.sentinel.nacos-rules.enabled=true",
                "ateng.cloud.sentinel.nacos-rules.data-id-prefix=sample-cloud-sentinel",
                "ateng.cloud.sentinel.nacos-rules.group-id=SENTINEL_GROUP"
        }
)
@AutoConfigureMockMvc
@Import(SentinelNacosRuleDynamicRefreshTest.MockNacosConfig.class)
class SentinelNacosRuleDynamicRefreshTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestMockNacosDataSourceFactory mockNacosFactory;

    @Autowired
    private SentinelNacosRuleSubscriber subscriber;

    @AfterEach
    void tearDown() {
        // 上下文零污染铁律：重置所有 RuleManager 规则
        FlowRuleManager.loadRules(Collections.emptyList());
        DegradeRuleManager.loadRules(Collections.emptyList());
        ParamFlowRuleManager.loadRules(Collections.emptyList());
        SystemRuleManager.loadRules(Collections.emptyList());
        AuthorityRuleManager.loadRules(Collections.emptyList());
    }

    @Test
    @DisplayName("端到端验证 Nacos 规则订阅器已成功约定挂载 5 种数据源")
    void shouldRegisterAllFiveDataSourcesByConvention() {
        assertThat(subscriber.getResolvedDataIdPrefix()).isEqualTo("sample-cloud-sentinel");
        assertThat(subscriber.getDataSources()).hasSize(5);
        assertThat(mockNacosFactory.getDataSourceMap()).containsKeys(
                "sample-cloud-sentinel-flow.json",
                "sample-cloud-sentinel-degrade.json",
                "sample-cloud-sentinel-param-flow.json",
                "sample-cloud-sentinel-system.json",
                "sample-cloud-sentinel-authority.json"
        );
    }

    @Test
    @DisplayName("动态推送 FlowRule 限流规则：实时热更并拦截超额请求 (HTTP 429 + FLOW_LIMIT)")
    void testDynamicFlowRulePushAndBlock() throws Exception {
        // 1. 未推送规则前，请求畅通无阻
        MvcResult passResult = mockMvc.perform(get("/api/test/flow"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode passJson = objectMapper.readTree(passResult.getResponse().getContentAsString());
        assertThat(passJson.get("code").asInt()).isEqualTo(200);

        // 2. 模拟从 Nacos 动态发布 FlowRule JSON (限制 QPS 为 2)
        String flowRuleJson = """
                [
                    {
                        "resource": "/api/test/flow",
                        "grade": 1,
                        "count": 2.0,
                        "limitApp": "default"
                    }
                ]
                """;
        mockNacosFactory.push("sample-cloud-sentinel-flow.json", flowRuleJson);

        // 强断言规则被热加载到 FlowRuleManager
        assertThat(FlowRuleManager.getRules()).hasSize(1);
        assertThat(FlowRuleManager.getRules().get(0).getResource()).isEqualTo("/api/test/flow");
        assertThat(FlowRuleManager.getRules().get(0).getCount()).isEqualTo(2.0);

        // 3. 发起调用：配额允许内通过，超过 2 次后触发限流拦截
        mockMvc.perform(get("/api/test/flow")).andExpect(status().isOk());

        MvcResult blockedResult = mockMvc.perform(get("/api/test/flow"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode blockedJson = objectMapper.readTree(blockedResult.getResponse().getContentAsString());
        assertThat(blockedJson.get("code").asInt()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getCode());
        assertThat(blockedJson.get("success").asBoolean()).isFalse();

        // 4. 模拟动态清空规则，热更新后立刻恢复畅通
        mockNacosFactory.push("sample-cloud-sentinel-flow.json", "[]");
        assertThat(FlowRuleManager.getRules()).isEmpty();

        mockMvc.perform(get("/api/test/flow")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("动态推送 ParamFlowRule 热点参数限流规则：实时热更并拦截 (HTTP 429 + PARAM_FLOW_LIMIT)")
    void testDynamicParamFlowRulePushAndBlock() throws Exception {
        // 1. 模拟从 Nacos 动态发布 ParamFlowRule JSON
        String paramFlowJson = """
                [
                    {
                        "resource": "custom-param-resource",
                        "grade": 1,
                        "paramIdx": 0,
                        "count": 1.0
                    }
                ]
                """;
        mockNacosFactory.push("sample-cloud-sentinel-param-flow.json", paramFlowJson);

        assertThat(ParamFlowRuleManager.getRules()).hasSize(1);
        assertThat(ParamFlowRuleManager.getRules().get(0).getResource()).isEqualTo("custom-param-resource");

        // 2. 针对指定参数调用：第 1 次通过，第 2 次被热点限流拦截
        mockMvc.perform(get("/api/test/param-flow").param("itemId", "goods-101"))
                .andExpect(status().isOk());

        MvcResult blockedResult = mockMvc.perform(get("/api/test/param-flow").param("itemId", "goods-101"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode json = objectMapper.readTree(blockedResult.getResponse().getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(SentinelResultCode.PARAM_FLOW_LIMIT.getCode());
    }

    @Test
    @DisplayName("动态推送 AuthorityRule 来源访问控制规则：白名单放行与非法来源阻断 (HTTP 429 + AUTHORITY_BLOCK)")
    void testDynamicAuthorityRulePushAndBlock() throws Exception {
        // 1. 模拟从 Nacos 动态发布白名单规则 (仅允许 trusted-gateway 访问)
        String authorityJson = """
                [
                    {
                        "resource": "/api/test/admin/secure",
                        "limitApp": "trusted-gateway",
                        "strategy": 0
                    }
                ]
                """;
        mockNacosFactory.push("sample-cloud-sentinel-authority.json", authorityJson);

        assertThat(AuthorityRuleManager.getRules()).hasSize(1);

        // 2. 未授权来源访问，立即被阻断
        MvcResult blockedResult = mockMvc.perform(get("/api/test/admin/secure")
                        .header("X-Origin-Service", "untrusted-caller"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode json = objectMapper.readTree(blockedResult.getResponse().getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(SentinelResultCode.AUTHORITY_BLOCK.getCode());

        // 3. 受信网关白名单来源访问，正常放行 200
        mockMvc.perform(get("/api/test/admin/secure")
                        .header("X-Origin-Service", "trusted-gateway"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("动态推送 DegradeRule 与 SystemRule：强断言规则被热加载到对应 RuleManager")
    void testDynamicDegradeAndSystemRulePush() {
        // 1. 动态推送 DegradeRule
        String degradeJson = """
                [
                    {
                        "resource": "/api/test/degrade",
                        "grade": 0,
                        "count": 50.0,
                        "timeWindow": 10
                    }
                ]
                """;
        mockNacosFactory.push("sample-cloud-sentinel-degrade.json", degradeJson);
        assertThat(DegradeRuleManager.getRules()).hasSize(1);
        assertThat(DegradeRuleManager.getRules().get(0).getResource()).isEqualTo("/api/test/degrade");

        // 2. 动态推送 SystemRule
        String systemJson = """
                [
                    {
                        "highestSystemLoad": 3.5
                    }
                ]
                """;
        mockNacosFactory.push("sample-cloud-sentinel-system.json", systemJson);
        assertThat(SystemRuleManager.getRules()).hasSize(1);
        assertThat(SystemRuleManager.getRules().get(0).getHighestSystemLoad()).isEqualTo(3.5);
    }

    /**
     * 测试配置类：注入离线自洽 Mock Nacos 数据源工厂
     */
    @TestConfiguration
    static class MockNacosConfig {

        @Bean
        public TestMockNacosDataSourceFactory testMockNacosDataSourceFactory() {
            return new TestMockNacosDataSourceFactory();
        }
    }

    /**
     * 脱网 Mock Nacos 数据源工厂实现
     */
    static class TestMockNacosDataSourceFactory implements NacosDataSourceFactory {

        private final Map<String, DynamicTestMockDataSource<?>> dataSourceMap = new ConcurrentHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> ReadableDataSource<String, T> createDataSource(
                Properties properties, String groupId, String dataId, Converter<String, T> converter) {
            DynamicTestMockDataSource<T> ds = new DynamicTestMockDataSource<>(converter);
            dataSourceMap.put(dataId, ds);
            return ds;
        }

        public void push(String dataId, String jsonPayload) {
            DynamicTestMockDataSource<?> ds = dataSourceMap.get(dataId);
            if (ds != null) {
                ds.push(jsonPayload);
            }
        }

        public Map<String, DynamicTestMockDataSource<?>> getDataSourceMap() {
            return dataSourceMap;
        }
    }

    /**
     * 动态可触发变更通知的 Mock 数据源
     */
    static class DynamicTestMockDataSource<T> extends AbstractDataSource<String, T> implements AutoCloseable {

        private final Converter<String, T> converter;
        private final DynamicSentinelProperty<T> property = new DynamicSentinelProperty<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        public DynamicTestMockDataSource(Converter<String, T> parser) {
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

        public void push(String source) {
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
