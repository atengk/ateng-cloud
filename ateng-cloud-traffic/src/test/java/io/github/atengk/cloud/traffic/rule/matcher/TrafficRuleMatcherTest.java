package io.github.atengk.cloud.traffic.rule.matcher;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.traffic.rule.hash.StickyHashPartitioner;
import io.github.atengk.cloud.traffic.rule.model.TrafficCondition;
import io.github.atengk.cloud.traffic.rule.model.TrafficConditionRelation;
import io.github.atengk.cloud.traffic.rule.model.TrafficFieldSource;
import io.github.atengk.cloud.traffic.rule.model.TrafficMatchOperator;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流量规则决策引擎单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class TrafficRuleMatcherTest {

    private final TrafficRuleMatcher matcher = new TrafficRuleMatcher();

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("空规则或空上下文防护测试")
    void testNullRulesOrContext() {
        TrafficEvaluationContext context = SimpleTrafficEvaluationContext.builder().build();
        assertThat(matcher.matchRule(null, context)).isNull();
        assertThat(matcher.matchRule(List.of(), context)).isNull();
        assertThat(matcher.matchRule(List.of(new TrafficRule()), null)).isNull();
        assertThat(matcher.matchTag(null, context)).isNull();
    }

    @Test
    @DisplayName("规则优先级调度测试 (数值越小优先级越高)")
    void testRulePriorityOrdering() {
        TrafficRule lowPriority = TrafficRule.builder()
                .id("rule-low")
                .priority(100)
                .targetTag("tag-low")
                .conditions(List.of(
                        TrafficCondition.builder()
                                .fieldSource(TrafficFieldSource.HEADER)
                                .fieldName("X-Role")
                                .operator(TrafficMatchOperator.EXACT)
                                .expectedValue("developer")
                                .build()
                ))
                .build();

        TrafficRule highPriority = TrafficRule.builder()
                .id("rule-high")
                .priority(10)
                .targetTag("tag-high")
                .conditions(List.of(
                        TrafficCondition.builder()
                                .fieldSource(TrafficFieldSource.HEADER)
                                .fieldName("X-Role")
                                .operator(TrafficMatchOperator.EXACT)
                                .expectedValue("developer")
                                .build()
                ))
                .build();

        TrafficEvaluationContext context = SimpleTrafficEvaluationContext.builder()
                .header("X-Role", "developer")
                .build();

        // 无论传入列表顺序如何，高优先级（数值 10）必须首先命中
        TrafficRule matched1 = matcher.matchRule(List.of(lowPriority, highPriority), context);
        assertThat(matched1).isNotNull();
        assertThat(matched1.getId()).isEqualTo("rule-high");
        assertThat(matched1.getTargetTag()).isEqualTo("tag-high");

        TrafficRule matched2 = matcher.matchRule(List.of(highPriority, lowPriority), context);
        assertThat(matched2).isNotNull();
        assertThat(matched2.getId()).isEqualTo("rule-high");
    }

    @Test
    @DisplayName("多维字段来源匹配测试 (Header, Query, Cookie, Path, User, Tenant, IP)")
    void testAllFieldSources() {
        TrafficRule rule = TrafficRule.builder()
                .id("rule-full-sources")
                .targetTag("gray-v2")
                .conditionRelation(TrafficConditionRelation.AND)
                .conditions(List.of(
                        TrafficCondition.builder().fieldSource(TrafficFieldSource.HEADER).fieldName("X-Device").expectedValue("iOS").build(),
                        TrafficCondition.builder().fieldSource(TrafficFieldSource.QUERY).fieldName("env").expectedValue("canary").build(),
                        TrafficCondition.builder().fieldSource(TrafficFieldSource.COOKIE).fieldName("session_type").expectedValue("internal").build(),
                        TrafficCondition.builder().fieldSource(TrafficFieldSource.PATH).operator(TrafficMatchOperator.PREFIX).expectedValue("/api/orders").build(),
                        TrafficCondition.builder().fieldSource(TrafficFieldSource.USER_ID).expectedValue("user_001").build(),
                        TrafficCondition.builder().fieldSource(TrafficFieldSource.TENANT_ID).expectedValue("tenant_bj").build(),
                        TrafficCondition.builder().fieldSource(TrafficFieldSource.IP).operator(TrafficMatchOperator.PREFIX).expectedValue("192.168.1.").build()
                ))
                .build();

        // 1. 完全匹配
        TrafficEvaluationContext matchedContext = SimpleTrafficEvaluationContext.builder()
                .header("X-Device", "iOS")
                .queryParam("env", "canary")
                .cookie("session_type", "internal")
                .path("/api/orders/pay")
                .userId("user_001")
                .tenantId("tenant_bj")
                .clientIp("192.168.1.50")
                .build();

        assertThat(matcher.matches(rule, matchedContext)).isTrue();
        assertThat(matcher.matchTag(List.of(rule), matchedContext)).isEqualTo("gray-v2");

        // 2. 单个条件不匹配 (如 Cookie 不一致)
        TrafficEvaluationContext mismatchedContext = SimpleTrafficEvaluationContext.builder()
                .header("X-Device", "iOS")
                .queryParam("env", "canary")
                .cookie("session_type", "public")
                .path("/api/orders/pay")
                .userId("user_001")
                .tenantId("tenant_bj")
                .clientIp("192.168.1.50")
                .build();

        assertThat(matcher.matches(rule, mismatchedContext)).isFalse();
    }

    @Test
    @DisplayName("OR 条件组合测试 (满足任意条件即命中)")
    void testOrConditionRelation() {
        TrafficRule rule = TrafficRule.builder()
                .id("rule-or")
                .targetTag("gray-or")
                .conditionRelation(TrafficConditionRelation.OR)
                .conditions(List.of(
                        TrafficCondition.builder().fieldSource(TrafficFieldSource.HEADER).fieldName("X-Beta").expectedValue("true").build(),
                        TrafficCondition.builder().fieldSource(TrafficFieldSource.USER_ID).expectedValue("vip_999").build()
                ))
                .build();

        // 命中条件 1
        TrafficEvaluationContext ctx1 = SimpleTrafficEvaluationContext.builder()
                .header("X-Beta", "true")
                .userId("normal_user")
                .build();
        assertThat(matcher.matches(rule, ctx1)).isTrue();

        // 命中条件 2
        TrafficEvaluationContext ctx2 = SimpleTrafficEvaluationContext.builder()
                .userId("vip_999")
                .build();
        assertThat(matcher.matches(rule, ctx2)).isTrue();

        // 两者均不满足
        TrafficEvaluationContext ctx3 = SimpleTrafficEvaluationContext.builder()
                .userId("normal_user")
                .build();
        assertThat(matcher.matches(rule, ctx3)).isFalse();
    }

    @Test
    @DisplayName("已禁用规则跳过测试")
    void testDisabledRuleSkipped() {
        TrafficRule disabledRule = TrafficRule.builder()
                .id("rule-disabled")
                .enabled(false)
                .targetTag("disabled-tag")
                .build();

        TrafficEvaluationContext context = SimpleTrafficEvaluationContext.builder().build();
        assertThat(matcher.matchRule(List.of(disabledRule), context)).isNull();
    }

    @Test
    @DisplayName("一致性哈希百分比切流集成测试 (Sticky Canary Partitioning)")
    void testPercentageStickyPartitioning() {
        TrafficRule rule = TrafficRule.builder()
                .id("rule-canary-30")
                .targetTag("canary-v3")
                .percentage(30)
                .hashKeySource(TrafficFieldSource.USER_ID)
                .build();

        // 1. 无条件且 percentage 为 100 时直接全量命中
        TrafficRule fullRule = TrafficRule.builder()
                .id("rule-full")
                .targetTag("canary-full")
                .percentage(100)
                .build();
        assertThat(matcher.matches(fullRule, SimpleTrafficEvaluationContext.builder().build())).isTrue();

        // 2. percentage 为 0 时直接不命中
        TrafficRule zeroRule = TrafficRule.builder()
                .id("rule-zero")
                .targetTag("canary-zero")
                .percentage(0)
                .build();
        assertThat(matcher.matches(zeroRule, SimpleTrafficEvaluationContext.builder().userId("user_1").build())).isFalse();

        // 3. 缺少哈希键时不命中放量
        TrafficEvaluationContext noUserContext = SimpleTrafficEvaluationContext.builder().build();
        assertThat(matcher.matches(rule, noUserContext)).isFalse();

        // 4. 指定以 Header 作为哈希源
        TrafficRule headerHashRule = TrafficRule.builder()
                .id("rule-header-hash")
                .targetTag("canary-header")
                .percentage(50)
                .hashKeySource(TrafficFieldSource.HEADER)
                .hashKeyName("X-Trace-Id")
                .build();

        TrafficEvaluationContext headerContext = SimpleTrafficEvaluationContext.builder()
                .header("X-Trace-Id", "trace-abc-12345")
                .build();
        boolean hitHeader = matcher.matches(headerHashRule, headerContext);
        // 验证粘性：同一 trace-id 重复判定 10 次结果绝对稳定
        for (int i = 0; i < 10; i++) {
            assertThat(matcher.matches(headerHashRule, headerContext)).isEqualTo(hitHeader);
        }
    }

    @Test
    @DisplayName("ThreadLocal 上下文兜底提取测试 (UserContextHolder 与 TenantContextHolder)")
    void testContextHolderFallback() {
        UserContextHolder.setUserId("user_context_holder_888");
        TenantContextHolder.setTenantId("tenant_holder_bj");

        TrafficRule rule = TrafficRule.builder()
                .id("rule-holder")
                .targetTag("gray-holder")
                .conditions(List.of(
                        TrafficCondition.builder().fieldSource(TrafficFieldSource.USER_ID).expectedValue("user_context_holder_888").build(),
                        TrafficCondition.builder().fieldSource(TrafficFieldSource.TENANT_ID).expectedValue("tenant_holder_bj").build()
                ))
                .build();

        // context 中不传显式 userId 和 tenantId，验证自动回退至 Holder
        TrafficEvaluationContext context = SimpleTrafficEvaluationContext.builder().build();
        assertThat(matcher.matches(rule, context)).isTrue();
    }

    @Test
    @DisplayName("提取字段值与哈希键时的防卫性判空测试")
    void testExtractFieldValueAndHashKeyNullDefenses() {
        // context 为 null 时安全返回 null 杜绝 NPE
        assertThat(matcher.extractFieldValue(TrafficFieldSource.HEADER, "X-Tag", null)).isNull();
        assertThat(matcher.extractFieldValue(null, null, null)).isNull();

        TrafficRule rule = TrafficRule.builder().id("r1").build();
        assertThat(matcher.extractHashKey(rule, null)).isNull();
        assertThat(matcher.extractHashKey(null, SimpleTrafficEvaluationContext.builder().build())).isNull();
    }

    @Test
    @DisplayName("percentageRange 优先级高于单值 percentage 测试")
    void testPercentageRange_PriorityOverPercentage() {
        // 配置了 percentageRange = [0, 20]，同时配置了 percentage = 100
        // 必须优先按 percentageRange 进行区间判定，而不是直接因 percentage=100 全量命中
        TrafficRule rule = TrafficRule.builder()
                .id("rule-range-priority")
                .targetTag("canary-v2")
                .percentageRange(0, 20)
                .percentage(100)
                .hashKeySource(TrafficFieldSource.USER_ID)
                .build();

        // 找出一个落在 [20, 100) 的用户
        String userNotInRange = null;
        for (int i = 0; i < 100; i++) {
            String uid = "uid_test_" + i;
            if (!matcher.getStickyHashPartitioner().isHitRange(uid, 0, 20)) {
                userNotInRange = uid;
                break;
            }
        }
        assertThat(userNotInRange).isNotNull();

        TrafficEvaluationContext ctx = SimpleTrafficEvaluationContext.builder()
                .userId(userNotInRange)
                .build();
        // 验证：尽管 percentage=100，但因为 percentageRange=[0, 20]，该用户依然未命中
        assertThat(matcher.matches(rule, ctx)).isFalse();
    }

    @Test
    @DisplayName("多版本金丝雀并行分桶互斥放量测试 (v2、v3、基线三向隔离)")
    void testPercentageRange_MultiCanaryMutualExclusion() {
        TrafficRule ruleV2 = TrafficRule.builder()
                .id("rule-canary-v2")
                .priority(1)
                .targetTag("v2")
                .percentageRange(0, 20)
                .hashKeySource(TrafficFieldSource.USER_ID)
                .build();

        TrafficRule ruleV3 = TrafficRule.builder()
                .id("rule-canary-v3")
                .priority(2)
                .targetTag("v3")
                .percentageRange(20, 50)
                .hashKeySource(TrafficFieldSource.USER_ID)
                .build();

        List<TrafficRule> rules = List.of(ruleV2, ruleV3);

        int sampleCount = 3000;
        int hitV2 = 0;
        int hitV3 = 0;
        int hitBaseline = 0;

        for (int i = 0; i < sampleCount; i++) {
            String userId = "canary_user_" + i;
            TrafficEvaluationContext ctx = SimpleTrafficEvaluationContext.builder()
                    .userId(userId)
                    .build();

            String tag = matcher.matchTag(rules, ctx);
            if ("v2".equals(tag)) {
                hitV2++;
            } else if ("v3".equals(tag)) {
                hitV3++;
            } else {
                assertThat(tag).isNull(); // 走基线
                hitBaseline++;
            }
        }

        assertThat((double) hitV2 / sampleCount).isBetween(0.18, 0.22);
        assertThat((double) hitV3 / sampleCount).isBetween(0.28, 0.32);
        assertThat((double) hitBaseline / sampleCount).isBetween(0.48, 0.52);
    }

    @Test
    @DisplayName("percentageRange 格式非法与越界防御测试 (绝不抛出异常，安全判定为未命中)")
    void testPercentageRange_InvalidRangeDefenses() {
        // 1. 数组长度不是 2
        TrafficRule invalidLengthRule = TrafficRule.builder()
                .id("r-len")
                .targetTag("v2")
                .percentageRange(new int[]{10})
                .build();
        assertThat(matcher.matches(invalidLengthRule, SimpleTrafficEvaluationContext.builder().userId("u1").build())).isFalse();

        // 2. min < 0
        TrafficRule negativeRule = TrafficRule.builder()
                .id("r-neg")
                .targetTag("v2")
                .percentageRange(new int[]{-5, 50})
                .build();
        assertThat(matcher.matches(negativeRule, SimpleTrafficEvaluationContext.builder().userId("u1").build())).isFalse();

        // 3. max > 100
        TrafficRule overflowRule = TrafficRule.builder()
                .id("r-overflow")
                .targetTag("v2")
                .percentageRange(new int[]{10, 105})
                .build();
        assertThat(matcher.matches(overflowRule, SimpleTrafficEvaluationContext.builder().userId("u1").build())).isFalse();

        // 4. min >= max (颠倒)
        TrafficRule invertedRule = TrafficRule.builder()
                .id("r-inverted")
                .targetTag("v2")
                .percentageRange(new int[]{60, 40})
                .build();
        assertThat(matcher.matches(invertedRule, SimpleTrafficEvaluationContext.builder().userId("u1").build())).isFalse();

        // 5. 缺少 hashKey
        TrafficRule noKeyRule = TrafficRule.builder()
                .id("r-nokey")
                .targetTag("v2")
                .percentageRange(0, 50)
                .build();
        assertThat(matcher.matches(noKeyRule, SimpleTrafficEvaluationContext.builder().build())).isFalse();
    }

    @Test
    @DisplayName("percentageRange 临界边界测试 (测试 0, 99, 100 临界分桶)")
    void testPercentageRange_CriticalBoundaries() {
        TrafficRule edge0Rule = TrafficRule.builder()
                .id("r-edge0")
                .targetTag("v-edge0")
                .percentageRange(0, 1)
                .build();
        TrafficRule edge99Rule = TrafficRule.builder()
                .id("r-edge99")
                .targetTag("v-edge99")
                .percentageRange(99, 100)
                .build();

        StickyHashPartitioner partitioner = new StickyHashPartitioner();
        boolean tested0 = false;
        boolean tested99 = false;

        for (int i = 0; i < 2000; i++) {
            String uid = "user_edge_test_" + i;
            int bucket = partitioner.partition(uid);
            TrafficEvaluationContext ctx = SimpleTrafficEvaluationContext.builder().userId(uid).build();
            if (bucket == 0) {
                assertThat(matcher.matches(edge0Rule, ctx)).isTrue();
                assertThat(matcher.matches(edge99Rule, ctx)).isFalse();
                tested0 = true;
            } else if (bucket == 99) {
                assertThat(matcher.matches(edge0Rule, ctx)).isFalse();
                assertThat(matcher.matches(edge99Rule, ctx)).isTrue();
                tested99 = true;
            }
            if (tested0 && tested99) {
                break;
            }
        }
        assertThat(tested0).isTrue();
        assertThat(tested99).isTrue();
    }

    @Test
    @DisplayName("TrafficRule percentageRange 序列化与反序列化及模型方法测试")
    void testTrafficRule_SerializationAndRangeEquals() throws Exception {
        TrafficRule rule = TrafficRule.builder()
                .id("rule-json")
                .name("JSON Range Rule")
                .priority(5)
                .targetTag("v2")
                .percentageRange(10, 30)
                .build();

        assertThat(rule.hasPercentageRange()).isTrue();
        assertThat(rule.getPercentageRange()).containsExactly(10, 30);

        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(rule);
        assertThat(json).contains("\"percentageRange\":[10,30]");

        TrafficRule deserialized = objectMapper.readValue(json, TrafficRule.class);
        assertThat(deserialized.getPercentageRange()).containsExactly(10, 30);
        assertThat(deserialized).isEqualTo(rule);
        assertThat(deserialized.hashCode()).isEqualTo(rule.hashCode());
        assertThat(deserialized.toString()).contains("percentageRange=[10, 30]");
    }
}
