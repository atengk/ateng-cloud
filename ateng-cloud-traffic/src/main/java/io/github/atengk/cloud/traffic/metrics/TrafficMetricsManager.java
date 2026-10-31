package io.github.atengk.cloud.traffic.metrics;

import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 全链路流量治理与金丝雀灰度 Micrometer 监控指标度量管理器
 * <p>
 * 输出 {@code ateng.cloud.traffic.requests.total}（Counter）多维请求指标，包含 service、tag 及 5+ 核心路由状态：
 * <ul>
 *     <li>{@code gray_hit}：成功命中灰度泳道实例</li>
 *     <li>{@code baseline_hit}：普通基线流量命中基线实例</li>
 *     <li>{@code fallback}：目标灰度实例全下线触发平滑降级至基线实例</li>
 *     <li>{@code strict_blocked}：金融级严格泳道隔离阻断</li>
 *     <li>{@code whitelist_stripped}：未授权标签被安全剥离降级</li>
 *     <li>{@code whitelist_rejected}：未授权标签被 4212 显式阻断</li>
 * </ul>
 * 以及 {@code ateng.cloud.traffic.rules.active}（Gauge）活跃规则总数度量。
 * 同时内部维护无锁内存计数器，支撑 /actuator/traffic 端点免 Prometheus 直接读取只读快照。
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class TrafficMetricsManager {

    public static final String METRIC_REQUESTS_TOTAL = "ateng.cloud.traffic.requests.total";
    public static final String METRIC_RULES_ACTIVE = "ateng.cloud.traffic.rules.active";

    public static final String STATUS_GRAY_HIT = "gray_hit";
    public static final String STATUS_BASELINE_HIT = "baseline_hit";
    public static final String STATUS_FALLBACK = "fallback";
    public static final String STATUS_STRICT_BLOCKED = "strict_blocked";
    public static final String STATUS_WHITELIST_STRIPPED = "whitelist_stripped";
    public static final String STATUS_WHITELIST_REJECTED = "whitelist_rejected";

    private final MeterRegistry meterRegistry;
    private final TrafficRuleProvider ruleProvider;

    private final LongAdder totalRequests = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> statusCounts = new ConcurrentHashMap<>();

    public TrafficMetricsManager() {
        this(null, null);
    }

    public TrafficMetricsManager(@Nullable MeterRegistry meterRegistry) {
        this(meterRegistry, null);
    }

    public TrafficMetricsManager(@Nullable MeterRegistry meterRegistry,
                                 @Nullable TrafficRuleProvider ruleProvider) {
        this.meterRegistry = meterRegistry;
        this.ruleProvider = ruleProvider;

        if (this.meterRegistry != null) {
            Gauge.builder(METRIC_RULES_ACTIVE, this, TrafficMetricsManager::getActiveRulesCount)
                    .description("当前内存中生效的流量灰度分流规则总数")
                    .register(this.meterRegistry);
        }
    }

    /**
     * 记录流量路由度量事件
     *
     * @param service 目标服务名称或请求源
     * @param tag     流量染色标签
     * @param status  路由状态 (gray_hit, baseline_hit, fallback, strict_blocked, whitelist_stripped, whitelist_rejected)
     */
    public void recordRequest(String service, String tag, String status) {
        String safeService = StringUtils.hasText(service) ? service.trim() : "unknown";
        String safeTag = StringUtils.hasText(tag) ? tag.trim() : "none";
        String safeStatus = StringUtils.hasText(status) ? status.trim() : "unknown";

        // 1. 更新内存统计快照
        totalRequests.increment();
        statusCounts.computeIfAbsent(safeStatus, k -> new LongAdder()).increment();

        // 2. 若存在 Micrometer 门面，累加 Counter
        if (meterRegistry != null) {
            Counter.builder(METRIC_REQUESTS_TOTAL)
                    .tag("service", safeService)
                    .tag("tag", safeTag)
                    .tag("status", safeStatus)
                    .description("全链路流量灰度调度与防护统计计数器")
                    .register(meterRegistry)
                    .increment();
        }
    }

    /**
     * 便捷方法：记录灰度命中
     */
    public void recordGrayHit(String service, String tag) {
        recordRequest(service, tag, STATUS_GRAY_HIT);
    }

    /**
     * 便捷方法：记录基线命中
     */
    public void recordBaselineHit(String service) {
        recordRequest(service, "baseline", STATUS_BASELINE_HIT);
    }

    /**
     * 便捷方法：记录平滑降级
     */
    public void recordFallback(String service, String tag) {
        recordRequest(service, tag, STATUS_FALLBACK);
    }

    /**
     * 便捷方法：记录严格泳道阻断
     */
    public void recordStrictBlocked(String service, String tag) {
        recordRequest(service, tag, STATUS_STRICT_BLOCKED);
    }

    /**
     * 便捷方法：记录白名单剥离
     */
    public void recordWhitelistStripped(String tag) {
        recordRequest("inbound", tag, STATUS_WHITELIST_STRIPPED);
    }

    /**
     * 便捷方法：记录白名单阻断
     */
    public void recordWhitelistRejected(String tag) {
        recordRequest("inbound", tag, STATUS_WHITELIST_REJECTED);
    }

    /**
     * 获取当前活跃规则数
     */
    public double getActiveRulesCount() {
        if (ruleProvider != null) {
            List<TrafficRule> rules = ruleProvider.getActiveRules();
            return rules != null ? rules.size() : 0;
        }
        return 0;
    }

    /**
     * 获取统计摘要快照
     */
    public Map<String, Object> getMetricsSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRequests", totalRequests.sum());
        summary.put("activeRulesCount", (int) getActiveRulesCount());

        Map<String, Long> statuses = new LinkedHashMap<>();
        // 保证关键指标 key 顺序呈现
        statuses.put(STATUS_GRAY_HIT, getStatusCount(STATUS_GRAY_HIT));
        statuses.put(STATUS_BASELINE_HIT, getStatusCount(STATUS_BASELINE_HIT));
        statuses.put(STATUS_FALLBACK, getStatusCount(STATUS_FALLBACK));
        statuses.put(STATUS_STRICT_BLOCKED, getStatusCount(STATUS_STRICT_BLOCKED));
        statuses.put(STATUS_WHITELIST_STRIPPED, getStatusCount(STATUS_WHITELIST_STRIPPED));
        statuses.put(STATUS_WHITELIST_REJECTED, getStatusCount(STATUS_WHITELIST_REJECTED));

        // 附加其他可能动态写入的状态
        for (Map.Entry<String, LongAdder> entry : statusCounts.entrySet()) {
            if (!statuses.containsKey(entry.getKey())) {
                statuses.put(entry.getKey(), entry.getValue().sum());
            }
        }
        summary.put("statusCounts", statuses);
        return Collections.unmodifiableMap(summary);
    }

    /**
     * 获取特定状态累计计数
     */
    public long getStatusCount(String status) {
        LongAdder adder = statusCounts.get(status);
        return adder != null ? adder.sum() : 0L;
    }

    /**
     * 获取总请求计数
     */
    public long getTotalRequests() {
        return totalRequests.sum();
    }

    /**
     * 重置所有内存计数器 (仅用于单元测试隔离)
     */
    public void reset() {
        totalRequests.reset();
        statusCounts.clear();
    }
}
