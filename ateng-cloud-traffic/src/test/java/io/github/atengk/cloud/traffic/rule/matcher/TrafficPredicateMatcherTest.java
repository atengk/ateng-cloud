package io.github.atengk.cloud.traffic.rule.matcher;

import io.github.atengk.cloud.traffic.rule.model.TrafficCondition;
import io.github.atengk.cloud.traffic.rule.model.TrafficFieldSource;
import io.github.atengk.cloud.traffic.rule.model.TrafficMatchOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纳秒级高性能多维谓词匹配器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class TrafficPredicateMatcherTest {

    private final TrafficPredicateMatcher matcher = new TrafficPredicateMatcher();

    @Test
    @DisplayName("null 条件防护测试")
    void testNullCondition() {
        assertThat(matcher.matches(null, "any")).isTrue();
    }

    @Test
    @DisplayName("EXACT 精确匹配测试 (区分大小写与忽略大小写)")
    void testExactMatch() {
        TrafficCondition caseSensitive = TrafficCondition.builder()
                .operator(TrafficMatchOperator.EXACT)
                .expectedValue("BetaUser")
                .caseSensitive(true)
                .build();

        assertThat(matcher.matches(caseSensitive, "BetaUser")).isTrue();
        assertThat(matcher.matches(caseSensitive, "betauser")).isFalse();
        assertThat(matcher.matches(caseSensitive, "Other")).isFalse();
        assertThat(matcher.matches(caseSensitive, null)).isFalse();

        TrafficCondition caseInsensitive = TrafficCondition.builder()
                .operator(TrafficMatchOperator.EXACT)
                .expectedValue("BetaUser")
                .caseSensitive(false)
                .build();

        assertThat(matcher.matches(caseInsensitive, "BetaUser")).isTrue();
        assertThat(matcher.matches(caseInsensitive, "betauser")).isTrue();
        assertThat(matcher.matches(caseInsensitive, "BETAUSER")).isTrue();
        assertThat(matcher.matches(caseInsensitive, null)).isFalse();

        TrafficCondition nullExpected = TrafficCondition.builder()
                .operator(TrafficMatchOperator.EXACT)
                .expectedValue(null)
                .build();
        assertThat(matcher.matches(nullExpected, null)).isTrue();
        assertThat(matcher.matches(nullExpected, "not_null")).isFalse();
    }

    @Test
    @DisplayName("IN 集合包含匹配测试 (列表模式与逗号分隔模式)")
    void testInMatch() {
        // 1. 列表模式
        TrafficCondition listCondition = TrafficCondition.builder()
                .operator(TrafficMatchOperator.IN)
                .expectedValues(List.of("1001", "1002", "1003"))
                .caseSensitive(true)
                .build();

        assertThat(matcher.matches(listCondition, "1001")).isTrue();
        assertThat(matcher.matches(listCondition, "1002")).isTrue();
        assertThat(matcher.matches(listCondition, "1003")).isTrue();
        assertThat(matcher.matches(listCondition, "1004")).isFalse();
        assertThat(matcher.matches(listCondition, null)).isFalse();

        // 2. 逗号分隔模式与空格裁剪
        TrafficCondition commaCondition = TrafficCondition.builder()
                .operator(TrafficMatchOperator.IN)
                .expectedValue("VIP, SVIP , GOLD")
                .caseSensitive(false)
                .build();

        assertThat(matcher.matches(commaCondition, "vip")).isTrue();
        assertThat(matcher.matches(commaCondition, "SVIP")).isTrue();
        assertThat(matcher.matches(commaCondition, "gold")).isTrue();
        assertThat(matcher.matches(commaCondition, "silver")).isFalse();
        assertThat(matcher.matches(commaCondition, null)).isFalse();
    }

    @Test
    @DisplayName("PREFIX 前缀匹配测试")
    void testPrefixMatch() {
        TrafficCondition prefixCondition = TrafficCondition.builder()
                .operator(TrafficMatchOperator.PREFIX)
                .expectedValue("/api/v2")
                .caseSensitive(true)
                .build();

        assertThat(matcher.matches(prefixCondition, "/api/v2/orders")).isTrue();
        assertThat(matcher.matches(prefixCondition, "/api/v2")).isTrue();
        assertThat(matcher.matches(prefixCondition, "/api/v1/orders")).isFalse();
        assertThat(matcher.matches(prefixCondition, "/API/V2/orders")).isFalse();
        assertThat(matcher.matches(prefixCondition, null)).isFalse();

        TrafficCondition insensitivePrefix = TrafficCondition.builder()
                .operator(TrafficMatchOperator.PREFIX)
                .expectedValue("/api/v2")
                .caseSensitive(false)
                .build();

        assertThat(matcher.matches(insensitivePrefix, "/API/V2/orders")).isTrue();
    }

    @Test
    @DisplayName("REGEX 正则表达式匹配测试")
    void testRegexMatch() {
        TrafficCondition regexCondition = TrafficCondition.builder()
                .operator(TrafficMatchOperator.REGEX)
                .expectedValue("^user_\\d{4}$")
                .caseSensitive(true)
                .build();

        assertThat(matcher.matches(regexCondition, "user_1234")).isTrue();
        assertThat(matcher.matches(regexCondition, "user_9999")).isTrue();
        assertThat(matcher.matches(regexCondition, "user_123")).isFalse();
        assertThat(matcher.matches(regexCondition, "USER_1234")).isFalse();
        assertThat(matcher.matches(regexCondition, "admin_1234")).isFalse();
        assertThat(matcher.matches(regexCondition, null)).isFalse();

        TrafficCondition insensitiveRegex = TrafficCondition.builder()
                .operator(TrafficMatchOperator.REGEX)
                .expectedValue("^user_\\d{4}$")
                .caseSensitive(false)
                .build();

        assertThat(matcher.matches(insensitiveRegex, "USER_1234")).isTrue();
    }

    @Test
    @DisplayName("REGEX 非法语法韧性防御测试 (不抛出异常，返回 false)")
    void testInvalidRegexResilience() {
        TrafficCondition invalidRegex = TrafficCondition.builder()
                .operator(TrafficMatchOperator.REGEX)
                .expectedValue("[a-z") // 未闭合括号语法错误
                .build();

        assertThat(matcher.matches(invalidRegex, "abc")).isFalse();
        matcher.clearCache();
    }

    @Test
    @DisplayName("SUFFIX 后缀匹配测试 (区分大小写与忽略大小写)")
    void testSuffixMatch() {
        TrafficCondition sensitiveSuffix = TrafficCondition.builder()
                .operator(TrafficMatchOperator.SUFFIX)
                .expectedValue(".internal")
                .caseSensitive(true)
                .build();

        assertThat(matcher.matches(sensitiveSuffix, "api.service.internal")).isTrue();
        assertThat(matcher.matches(sensitiveSuffix, ".internal")).isTrue();
        assertThat(matcher.matches(sensitiveSuffix, "api.service.INTERNAL")).isFalse();
        assertThat(matcher.matches(sensitiveSuffix, "api.service.com")).isFalse();
        assertThat(matcher.matches(sensitiveSuffix, "short")).isFalse();
        assertThat(matcher.matches(sensitiveSuffix, null)).isFalse();

        TrafficCondition insensitiveSuffix = TrafficCondition.builder()
                .operator(TrafficMatchOperator.SUFFIX)
                .expectedValue(".internal")
                .caseSensitive(false)
                .build();

        assertThat(matcher.matches(insensitiveSuffix, "API.SERVICE.INTERNAL")).isTrue();
        assertThat(matcher.matches(insensitiveSuffix, "api.service.Internal")).isTrue();

        TrafficCondition nullSuffix = TrafficCondition.builder()
                .operator(TrafficMatchOperator.SUFFIX)
                .expectedValue(null)
                .build();
        assertThat(matcher.matches(nullSuffix, "test")).isFalse();
    }

    @Test
    @DisplayName("CONTAINS 包含匹配测试 (区分大小写与忽略大小写)")
    void testContainsMatch() {
        TrafficCondition sensitiveContains = TrafficCondition.builder()
                .operator(TrafficMatchOperator.CONTAINS)
                .expectedValue("OrderService")
                .caseSensitive(true)
                .build();

        assertThat(matcher.matches(sensitiveContains, "PreOrderServicePost")).isTrue();
        assertThat(matcher.matches(sensitiveContains, "OrderService")).isTrue();
        assertThat(matcher.matches(sensitiveContains, "orderservice")).isFalse();
        assertThat(matcher.matches(sensitiveContains, "Other")).isFalse();
        assertThat(matcher.matches(sensitiveContains, null)).isFalse();

        TrafficCondition insensitiveContains = TrafficCondition.builder()
                .operator(TrafficMatchOperator.CONTAINS)
                .expectedValue("OrderService")
                .caseSensitive(false)
                .build();

        assertThat(matcher.matches(insensitiveContains, "PRE_orderservice_POST")).isTrue();
        assertThat(matcher.matches(insensitiveContains, "ORDERSERVICE")).isTrue();

        TrafficCondition nullContains = TrafficCondition.builder()
                .operator(TrafficMatchOperator.CONTAINS)
                .expectedValue(null)
                .build();
        assertThat(matcher.matches(nullContains, "test")).isFalse();
    }
}
