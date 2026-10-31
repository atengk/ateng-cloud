package io.github.atengk.cloud.traffic.metrics;

import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流量治理 Micrometer 指标度量管理器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class TrafficMetricsManagerTest {

    @Test
    @DisplayName("测试集成 MeterRegistry 时正确注册 Counter 与 Gauge 指标")
    void testMetricsWithMeterRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TrafficRule r1 = TrafficRule.builder().id("r1").targetTag("v2").enabled(true).build();
        TrafficRule r2 = TrafficRule.builder().id("r2").targetTag("v3").enabled(true).build();
        TrafficRule r3 = TrafficRule.builder().id("r3").targetTag("v4").enabled(false).build(); // 禁用
        TrafficRuleProvider provider = () -> List.of(r1, r2, r3);

        TrafficMetricsManager manager = new TrafficMetricsManager(registry, provider);

        // 1. 验证 Gauge 活跃规则数 (2 条启用)
        double activeRules = registry.get(TrafficMetricsManager.METRIC_RULES_ACTIVE).gauge().value();
        assertThat(activeRules).isEqualTo(2.0);

        // 2. 模拟触发各类调度事件
        manager.recordGrayHit("order-service", "v2");
        manager.recordGrayHit("order-service", "v2");
        manager.recordBaselineHit("order-service");
        manager.recordFallback("inventory-service", "v2");
        manager.recordStrictBlocked("pay-service", "v3");
        manager.recordWhitelistStripped("malicious-tag");
        manager.recordWhitelistRejected("forged-tag");

        // 3. 验证 Counter 计数
        Counter grayHitCounter = registry.get(TrafficMetricsManager.METRIC_REQUESTS_TOTAL)
                .tag("service", "order-service")
                .tag("tag", "v2")
                .tag("status", TrafficMetricsManager.STATUS_GRAY_HIT)
                .counter();
        assertThat(grayHitCounter.count()).isEqualTo(2.0);

        Counter fallbackCounter = registry.get(TrafficMetricsManager.METRIC_REQUESTS_TOTAL)
                .tag("service", "inventory-service")
                .tag("tag", "v2")
                .tag("status", TrafficMetricsManager.STATUS_FALLBACK)
                .counter();
        assertThat(fallbackCounter.count()).isEqualTo(1.0);

        Counter strictBlockedCounter = registry.get(TrafficMetricsManager.METRIC_REQUESTS_TOTAL)
                .tag("service", "pay-service")
                .tag("tag", "v3")
                .tag("status", TrafficMetricsManager.STATUS_STRICT_BLOCKED)
                .counter();
        assertThat(strictBlockedCounter.count()).isEqualTo(1.0);

        Counter strippedCounter = registry.get(TrafficMetricsManager.METRIC_REQUESTS_TOTAL)
                .tag("service", "inbound")
                .tag("tag", "malicious-tag")
                .tag("status", TrafficMetricsManager.STATUS_WHITELIST_STRIPPED)
                .counter();
        assertThat(strippedCounter.count()).isEqualTo(1.0);

        // 4. 验证内存摘要快照
        Map<String, Object> summary = manager.getMetricsSummary();
        assertThat(summary.get("totalRequests")).isEqualTo(7L);
        assertThat(summary.get("activeRulesCount")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        Map<String, Long> statusCounts = (Map<String, Long>) summary.get("statusCounts");
        assertThat(statusCounts.get(TrafficMetricsManager.STATUS_GRAY_HIT)).isEqualTo(2L);
        assertThat(statusCounts.get(TrafficMetricsManager.STATUS_BASELINE_HIT)).isEqualTo(1L);
        assertThat(statusCounts.get(TrafficMetricsManager.STATUS_FALLBACK)).isEqualTo(1L);
        assertThat(statusCounts.get(TrafficMetricsManager.STATUS_STRICT_BLOCKED)).isEqualTo(1L);
        assertThat(statusCounts.get(TrafficMetricsManager.STATUS_WHITELIST_STRIPPED)).isEqualTo(1L);
        assertThat(statusCounts.get(TrafficMetricsManager.STATUS_WHITELIST_REJECTED)).isEqualTo(1L);
    }

    @Test
    @DisplayName("测试无 MeterRegistry 时内存无锁统计依然平稳运作且不抛出异常")
    void testMetricsWithoutMeterRegistry() {
        TrafficMetricsManager manager = new TrafficMetricsManager(null, null);

        manager.recordGrayHit("user-service", "gray-1");
        manager.recordBaselineHit("user-service");

        assertThat(manager.getTotalRequests()).isEqualTo(2L);
        assertThat(manager.getStatusCount(TrafficMetricsManager.STATUS_GRAY_HIT)).isEqualTo(1L);
        assertThat(manager.getStatusCount(TrafficMetricsManager.STATUS_BASELINE_HIT)).isEqualTo(1L);

        // 重置
        manager.reset();
        assertThat(manager.getTotalRequests()).isEqualTo(0L);
        assertThat(manager.getStatusCount(TrafficMetricsManager.STATUS_GRAY_HIT)).isEqualTo(0L);
    }

    @Test
    @DisplayName("测试边界空值与非法字符串输入容错防卫")
    void testDefensiveHandlingOnNullAndBlankInputs() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TrafficMetricsManager manager = new TrafficMetricsManager(registry, null);

        manager.recordRequest(null, null, null);

        assertThat(manager.getTotalRequests()).isEqualTo(1L);
        assertThat(manager.getStatusCount("unknown")).isEqualTo(1L);

        Counter counter = registry.get(TrafficMetricsManager.METRIC_REQUESTS_TOTAL)
                .tag("service", "unknown")
                .tag("tag", "none")
                .tag("status", "unknown")
                .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
