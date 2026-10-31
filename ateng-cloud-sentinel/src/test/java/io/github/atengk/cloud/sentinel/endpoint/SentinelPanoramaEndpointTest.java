package io.github.atengk.cloud.sentinel.endpoint;

import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import io.github.atengk.cloud.sentinel.datasource.SentinelNacosRuleSubscriber;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelNacosRuleProperties;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SentinelPanoramaEndpoint 全景自省诊断端点单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class SentinelPanoramaEndpointTest {

    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        tearDown();
        environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "demo-sentinel-service");
    }

    @AfterEach
    void tearDown() {
        // 遵循上下文零污染铁律：测试结束后无条件清空所有 Sentinel 内存规则
        FlowRuleManager.loadRules(Collections.emptyList());
        DegradeRuleManager.loadRules(Collections.emptyList());
        ParamFlowRuleManager.loadRules(Collections.emptyList());
        SystemRuleManager.loadRules(Collections.emptyList());
        AuthorityRuleManager.loadRules(Collections.emptyList());
    }

    @Test
    @DisplayName("测试缺省/空依赖注入时的平滑防御与空安全保底")
    void shouldReportSafelyWhenDependenciesAreNull() {
        SentinelPanoramaEndpoint endpoint = new SentinelPanoramaEndpoint(null, null, null, null);

        SentinelPanoramaReport report = endpoint.report();

        assertThat(report).isNotNull();
        assertThat(report.status()).isEqualTo("UP");
        assertThat(report.appName()).isEqualTo("unknown");
        assertThat(report.sentinelVersion()).isNotBlank();
        assertThat(report.timestamp()).isGreaterThan(0);

        assertThat(report.nacosRules()).isNotNull();
        assertThat(report.nacosRules().enabled()).isFalse();
        assertThat(report.nacosRules().subscribed()).isFalse();
        assertThat(report.nacosRules().dataSourceCount()).isEqualTo(0);

        assertThat(report.inMemoryRules()).isNotNull();
        assertThat(report.inMemoryRules().totalRuleCount()).isEqualTo(0);

        assertThat(report.webMvcConfig()).isNotNull();
        assertThat(report.webMvcConfig().urlClean().enabled()).isTrue();

        assertThat(report.blockMetrics()).isNotNull();
        assertThat(report.blockMetrics().totalBlocked()).isEqualTo(0);
        assertThat(report.blockMetrics().lastBlockEvent()).isNull();
    }

    @Test
    @DisplayName("测试 Nacos 动态规则数据源概览与敏感凭据脱敏")
    void shouldMaskSensitiveCredentialsInNacosReport() {
        AtengSentinelNacosRuleProperties nacosProps = new AtengSentinelNacosRuleProperties();
        nacosProps.setEnabled(true);
        nacosProps.setDataIdPrefix("order-service");
        nacosProps.setGroupId("SENTINEL_GROUP");
        nacosProps.setServerAddr("192.168.1.100:8848");
        nacosProps.setNamespace("prod-ns");
        nacosProps.setUsername("nacos-admin");
        nacosProps.setPassword("secret-password-123");
        nacosProps.setAccessKey("ak-my-key");
        nacosProps.setSecretKey("sk-my-secret");

        SentinelNacosRuleSubscriber subscriber = mock(SentinelNacosRuleSubscriber.class);
        when(subscriber.getProperties()).thenReturn(nacosProps);
        when(subscriber.getResolvedDataIdPrefix()).thenReturn("order-service");
        when(subscriber.getEffectiveServerAddr()).thenReturn("192.168.1.100:8848");
        when(subscriber.getEffectiveNamespace()).thenReturn("prod-ns");
        when(subscriber.getEnvironment()).thenReturn(environment);
        when(subscriber.getDataSources()).thenReturn(List.of(mock(), mock(), mock(), mock(), mock()));

        @SuppressWarnings("unchecked")
        ObjectProvider<SentinelNacosRuleSubscriber> subscriberProvider = mock(ObjectProvider.class);
        when(subscriberProvider.getIfAvailable()).thenReturn(subscriber);

        SentinelPanoramaEndpoint endpoint = new SentinelPanoramaEndpoint(null, subscriberProvider, null, environment);

        SentinelPanoramaReport report = endpoint.report();

        assertThat(report.appName()).isEqualTo("demo-sentinel-service");
        SentinelPanoramaReport.NacosRulesReport nacos = report.nacosRules();
        assertThat(nacos).isNotNull();
        assertThat(nacos.enabled()).isTrue();
        assertThat(nacos.prefix()).isEqualTo("order-service");
        assertThat(nacos.groupId()).isEqualTo("SENTINEL_GROUP");
        assertThat(nacos.serverAddr()).isEqualTo("192.168.1.100:8848");
        assertThat(nacos.namespace()).isEqualTo("prod-ns");
        assertThat(nacos.username()).isEqualTo("nacos-admin");
        // 关键安全断言：密码、AK、SK 严禁明文泄露，必须脱敏为 ******
        assertThat(nacos.password()).isEqualTo("******");
        assertThat(nacos.accessKey()).isEqualTo("******");
        assertThat(nacos.secretKey()).isEqualTo("******");
        assertThat(nacos.subscribed()).isTrue();
        assertThat(nacos.dataSourceCount()).isEqualTo(5);
        assertThat(nacos.dataSources()).contains(
                "order-service-flow.json",
                "order-service-degrade.json",
                "order-service-param-flow.json",
                "order-service-system.json",
                "order-service-authority.json"
        );
    }

    @Test
    @DisplayName("测试实时抓取并映射 Sentinel 内存中 5 大规则快照")
    void shouldReflectInMemoryRulesAccurately() {
        // 1. 注入 FlowRule
        FlowRule flow = new FlowRule("/api/test-flow");
        flow.setCount(20.0);
        flow.setGrade(1);
        flow.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(flow));

        // 2. 注入 DegradeRule
        DegradeRule degrade = new DegradeRule("/api/test-degrade");
        degrade.setCount(50.0);
        degrade.setTimeWindow(10);
        degrade.setGrade(0);
        degrade.setMinRequestAmount(5);
        DegradeRuleManager.loadRules(List.of(degrade));

        // 3. 注入 ParamFlowRule
        ParamFlowRule param = new ParamFlowRule("/api/test-param");
        param.setParamIdx(0);
        param.setCount(5.0);
        param.setDurationInSec(1);
        ParamFlowRuleManager.loadRules(List.of(param));

        // 4. 注入 SystemRule (单指标对应 Sentinel 内部单个生效规则)
        SystemRule system = new SystemRule();
        system.setHighestSystemLoad(3.0);
        SystemRuleManager.loadRules(List.of(system));

        // 5. 注入 AuthorityRule
        AuthorityRule auth = new AuthorityRule();
        auth.setResource("/api/test-auth");
        auth.setLimitApp("app-whitelist");
        auth.setStrategy(0);
        AuthorityRuleManager.loadRules(List.of(auth));

        SentinelPanoramaEndpoint endpoint = new SentinelPanoramaEndpoint(null, null, null, environment);

        SentinelPanoramaReport report = endpoint.report();

        SentinelPanoramaReport.InMemoryRulesReport inMemory = report.inMemoryRules();
        assertThat(inMemory).isNotNull();
        assertThat(inMemory.totalRuleCount()).isEqualTo(5);
        assertThat(inMemory.flowRuleCount()).isEqualTo(1);
        assertThat(inMemory.degradeRuleCount()).isEqualTo(1);
        assertThat(inMemory.paramFlowRuleCount()).isEqualTo(1);
        assertThat(inMemory.systemRuleCount()).isEqualTo(1);
        assertThat(inMemory.authorityRuleCount()).isEqualTo(1);

        assertThat(inMemory.flowRules().get(0).resource()).isEqualTo("/api/test-flow");
        assertThat(inMemory.flowRules().get(0).count()).isEqualTo(20.0);

        assertThat(inMemory.degradeRules().get(0).resource()).isEqualTo("/api/test-degrade");
        assertThat(inMemory.degradeRules().get(0).timeWindow()).isEqualTo(10);

        assertThat(inMemory.paramFlowRules().get(0).resource()).isEqualTo("/api/test-param");
        assertThat(inMemory.paramFlowRules().get(0).paramIdx()).isEqualTo(0);

        assertThat(inMemory.systemRules().get(0).highestSystemLoad()).isEqualTo(3.0);

        assertThat(inMemory.authorityRules().get(0).resource()).isEqualTo("/api/test-auth");
        assertThat(inMemory.authorityRules().get(0).limitApp()).isEqualTo("app-whitelist");
    }

    @Test
    @DisplayName("测试 Web MVC 配置与阻断统计全真映射")
    void shouldMapWebMvcAndBlockMetricsAccurately() {
        AtengSentinelProperties properties = new AtengSentinelProperties();
        properties.getUrlClean().setExcludePatterns(List.of("/actuator/**", "/error"));
        properties.getOriginParser().setOriginHeader("X-Tenant-Origin");
        properties.getBlockHandler().setHttpStatusAlign(false);

        @SuppressWarnings("unchecked")
        ObjectProvider<AtengSentinelProperties> propertiesProvider = mock(ObjectProvider.class);
        when(propertiesProvider.getIfAvailable()).thenReturn(properties);

        SentinelBlockCounter counter = new SentinelBlockCounter();
        counter.recordBlock(new FlowException("flow-limit"), "/api/pay", "192.168.1.50");

        @SuppressWarnings("unchecked")
        ObjectProvider<SentinelBlockCounter> counterProvider = mock(ObjectProvider.class);
        when(counterProvider.getIfAvailable()).thenReturn(counter);

        SentinelPanoramaEndpoint endpoint = new SentinelPanoramaEndpoint(propertiesProvider, null, counterProvider, environment);

        SentinelPanoramaReport report = endpoint.report();

        // 验证 WebMvc 配置快照
        SentinelPanoramaReport.WebMvcConfigReport webMvc = report.webMvcConfig();
        assertThat(webMvc.urlClean().excludePatternCount()).isEqualTo(2);
        assertThat(webMvc.urlClean().excludePatterns()).contains("/actuator/**", "/error");
        assertThat(webMvc.originParser().originHeader()).isEqualTo("X-Tenant-Origin");
        assertThat(webMvc.blockHandler().httpStatusAlign()).isFalse();

        // 验证阻断统计快照
        SentinelPanoramaReport.BlockMetricsReport metrics = report.blockMetrics();
        assertThat(metrics.totalBlocked()).isEqualTo(1);
        assertThat(metrics.flowCount()).isEqualTo(1);
        assertThat(metrics.lastBlockEvent()).isNotNull();
        assertThat(metrics.lastBlockEvent().resource()).isEqualTo("/api/pay");
        assertThat(metrics.lastBlockEvent().clientIp()).isEqualTo("192.168.1.50");
        assertThat(metrics.lastBlockEvent().ruleType()).isEqualTo("FLOW");
    }
}
