package io.github.atengk.cloud.traffic.actuator;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.metrics.TrafficMetricsManager;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.properties.TrafficWhitelistAction;
import io.github.atengk.cloud.traffic.rule.hash.StickyHashPartitioner;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficPredicateMatcher;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
import io.github.atengk.cloud.traffic.rule.model.TrafficCondition;
import io.github.atengk.cloud.traffic.rule.model.TrafficFieldSource;
import io.github.atengk.cloud.traffic.rule.model.TrafficMatchOperator;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流量治理 Actuator 诊断与沙箱模拟端点单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class TrafficEndpointTest {

    private AtengTrafficProperties properties;
    private MockEnvironment environment;
    private TrafficMetricsManager metricsManager;
    private TrafficRuleMatcher ruleMatcher;
    private StickyHashPartitioner stickyHashPartitioner;
    private TrafficPredicateMatcher predicateMatcher;

    @BeforeEach
    void setUp() {
        properties = new AtengTrafficProperties();
        environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "test-gateway-service");
        metricsManager = new TrafficMetricsManager(null, null);
        stickyHashPartitioner = new StickyHashPartitioner();
        predicateMatcher = new TrafficPredicateMatcher();
        ruleMatcher = new TrafficRuleMatcher(stickyHashPartitioner, predicateMatcher);

        TrafficContextHolder.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        TrafficContextHolder.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试只读操作正确返回配置快照、规则列表与度量统计")
    void testGetTrafficOverview() {
        TrafficRule rule1 = TrafficRule.builder()
                .id("rule-1")
                .name("VIP用户灰度规则")
                .priority(1)
                .targetTag("vip-v2")
                .enabled(true)
                .build();
        TrafficRuleProvider provider = () -> List.of(rule1);

        properties.setAllowedTags(Set.of("vip-v2", "beta"));
        properties.setWhitelistAction(TrafficWhitelistAction.REJECT);
        properties.setStrictLane(true);

        metricsManager.recordGrayHit("order-service", "vip-v2");

        TrafficEndpoint endpoint = new TrafficEndpoint(
                properties, provider, ruleMatcher, stickyHashPartitioner, predicateMatcher, metricsManager, environment
        );

        Map<String, Object> overview = endpoint.getTrafficOverview();

        assertThat(overview.get("status")).isEqualTo("UP");
        assertThat(overview.get("serviceName")).isEqualTo("test-gateway-service");

        // 验证 properties 快照
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) overview.get("properties");
        assertThat(props.get("strictLane")).isEqualTo(true);
        assertThat(props.get("whitelistAction")).isEqualTo("REJECT");
        assertThat(props.get("allowedTags")).isEqualTo(Set.of("vip-v2", "beta"));

        // 验证 rules 快照
        @SuppressWarnings("unchecked")
        Map<String, Object> rules = (Map<String, Object>) overview.get("rules");
        assertThat(rules.get("activeRulesCount")).isEqualTo(1);

        // 验证 metrics 统计
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) overview.get("metrics");
        assertThat(metrics.get("totalRequests")).isEqualTo(1L);
    }

    @Test
    @DisplayName("测试模拟沙箱：Header 命中条件并返回预测规则与目标标签且绝无副作用")
    void testSimulate_HeaderConditionHit() {
        TrafficRule rule = TrafficRule.builder()
                .id("rule-device-ios")
                .name("iOS 专属灰度")
                .priority(10)
                .targetTag("canary-ios")
                .enabled(true)
                .conditions(List.of(
                        TrafficCondition.builder()
                                .fieldSource(TrafficFieldSource.HEADER)
                                .fieldName("X-Device-Type")
                                .operator(TrafficMatchOperator.EXACT)
                                .expectedValue("iOS")
                                .build()
                ))
                .build();
        TrafficRuleProvider provider = () -> List.of(rule);

        TrafficEndpoint endpoint = new TrafficEndpoint(
                properties, provider, ruleMatcher, stickyHashPartitioner, predicateMatcher, metricsManager, environment
        );

        // 发起沙箱模拟调用
        Map<String, Object> result = endpoint.simulate(
                Map.of("X-Device-Type", "iOS"),
                null, null, null, null, null, null
        );

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("matched")).isEqualTo(true);
        assertThat(result.get("matchedRuleId")).isEqualTo("rule-device-ios");
        assertThat(result.get("targetTag")).isEqualTo("canary-ios");
        assertThat(result.get("priority")).isEqualTo(10);
        assertThat((long) result.get("durationNanos")).isGreaterThan(0L);

        // 强断言：沙箱执行完毕后，所有真实的线程上下文与 MDC 绝对保持洁净 (零污染)
        assertThat(TrafficContextHolder.getTag()).isNull();
        assertThat(UserContextHolder.getUserId()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isNull();
    }

    @Test
    @DisplayName("测试模拟沙箱：未命中任何规则时保底返回未命中状态")
    void testSimulate_NoMatch() {
        TrafficRule rule = TrafficRule.builder()
                .id("rule-android")
                .targetTag("canary-android")
                .enabled(true)
                .conditions(List.of(
                        TrafficCondition.builder()
                                .fieldSource(TrafficFieldSource.HEADER)
                                .fieldName("X-Device-Type")
                                .operator(TrafficMatchOperator.EXACT)
                                .expectedValue("Android")
                                .build()
                ))
                .build();
        TrafficRuleProvider provider = () -> List.of(rule);

        TrafficEndpoint endpoint = new TrafficEndpoint(
                properties, provider, ruleMatcher, stickyHashPartitioner, predicateMatcher, metricsManager, environment
        );

        Map<String, Object> result = endpoint.simulate(
                Map.of("X-Device-Type", "WindowsPhone"),
                null, null, null, null, null, null
        );

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("matched")).isEqualTo(false);
        assertThat(result.get("matchedRuleId")).isNull();
        assertThat(result.get("targetTag")).isNull();
    }

    @Test
    @DisplayName("测试模拟沙箱：百分比区间切流与白名单预测校验")
    void testSimulate_PercentageRangeAndWhitelistPrediction() {
        // 配置白名单仅允许 v2
        properties.setAllowedTags(Set.of("v2"));
        properties.setWhitelistAction(TrafficWhitelistAction.REJECT);

        TrafficRule rule = TrafficRule.builder()
                .id("rule-range-v3")
                .targetTag("v3") // 未在白名单中
                .priority(5)
                .enabled(true)
                .percentageRange(new int[]{0, 100}) // 100% 命中区间
                .hashKeySource(TrafficFieldSource.USER_ID)
                .build();
        TrafficRuleProvider provider = () -> List.of(rule);

        TrafficEndpoint endpoint = new TrafficEndpoint(
                properties, provider, ruleMatcher, stickyHashPartitioner, predicateMatcher, metricsManager, environment
        );

        Map<String, Object> result = endpoint.simulate(
                null, null, null, "/api/orders", "user-10086", "tenant-alpha", "192.168.1.10"
        );

        assertThat(result.get("matched")).isEqualTo(true);
        assertThat(result.get("matchedRuleId")).isEqualTo("rule-range-v3");
        assertThat(result.get("targetTag")).isEqualTo("v3");
        assertThat(result.get("hashKey")).isEqualTo("user-10086");
        assertThat(result.get("hashBucket")).isNotNull();
        // 验证白名单预测：v3 不在 allowedTags 中，whitelistAllowed 应为 false
        assertThat(result.get("whitelistAllowed")).isEqualTo(false);
        assertThat(result.get("whitelistAction")).isEqualTo("REJECT");
    }

    @Test
    @DisplayName("测试 simulateWithAction 路径指令参数校验")
    void testSimulateWithAction() {
        TrafficEndpoint endpoint = new TrafficEndpoint(
                properties, null, ruleMatcher, stickyHashPartitioner, predicateMatcher, metricsManager, environment
        );

        // 合法指令 simulate
        Map<String, Object> validResult = endpoint.simulateWithAction(
                "simulate", null, null, null, null, null, null, null
        );
        assertThat(validResult.get("success")).isEqualTo(true);

        // 非法指令
        Map<String, Object> invalidResult = endpoint.simulateWithAction(
                "invalidAction", null, null, null, null, null, null, null
        );
        assertThat(invalidResult.get("success")).isEqualTo(false);
        assertThat((String) invalidResult.get("error")).contains("Unsupported action");
    }
}
