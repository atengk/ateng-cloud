package io.github.atengk.cloud.traffic.actuator;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.cloud.traffic.metrics.TrafficMetricsManager;
import io.github.atengk.cloud.traffic.nacos.NacosTrafficRuleListener;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.rule.hash.StickyHashPartitioner;
import io.github.atengk.cloud.traffic.rule.matcher.SimpleTrafficEvaluationContext;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficPredicateMatcher;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
import io.github.atengk.cloud.traffic.rule.model.TrafficCondition;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全链路流量治理与金丝雀灰度运维诊断端点
 * <p>
 * 暴露路径为 {@code /actuator/traffic}：
 * <ul>
 *     <li>GET {@code /actuator/traffic}：只读全景状态查询（规则列表、配置快照、Nacos 同步状态、路由统计）</li>
 *     <li>POST {@code /actuator/traffic/simulate}：纳秒级无副作用规则模拟匹配沙箱</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Endpoint(id = "traffic")
public class TrafficEndpoint {

    private final AtengTrafficProperties properties;
    private final TrafficRuleProvider ruleProvider;
    private final TrafficRuleMatcher ruleMatcher;
    private final StickyHashPartitioner stickyHashPartitioner;
    private final TrafficPredicateMatcher predicateMatcher;
    private final TrafficMetricsManager metricsManager;
    private final Environment environment;

    public TrafficEndpoint(AtengTrafficProperties properties,
                           @Nullable TrafficRuleProvider ruleProvider,
                           @Nullable TrafficRuleMatcher ruleMatcher,
                           @Nullable StickyHashPartitioner stickyHashPartitioner,
                           @Nullable TrafficPredicateMatcher predicateMatcher,
                           @Nullable TrafficMetricsManager metricsManager,
                           @Nullable Environment environment) {
        this.properties = properties != null ? properties : new AtengTrafficProperties();
        this.ruleProvider = ruleProvider;
        this.ruleMatcher = ruleMatcher != null ? ruleMatcher : new TrafficRuleMatcher();
        this.stickyHashPartitioner = stickyHashPartitioner != null ? stickyHashPartitioner : new StickyHashPartitioner();
        this.predicateMatcher = predicateMatcher != null ? predicateMatcher : new TrafficPredicateMatcher();
        this.metricsManager = metricsManager;
        this.environment = environment;
    }

    /**
     * 读取当前微服务流量治理全景状态与诊断信息
     *
     * @return 全景状态字典
     */
    @ReadOperation
    public Map<String, Object> getTrafficOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("status", properties.isEnabled() ? "UP" : "DISABLED");
        overview.put("serviceName", environment != null ? environment.getProperty("spring.application.name", "unknown") : "unknown");

        // 1. 配置项快照
        Map<String, Object> configSnapshot = new LinkedHashMap<>();
        configSnapshot.put("enabled", properties.isEnabled());
        configSnapshot.put("headerName", properties.getHeaderName());
        configSnapshot.put("paramName", properties.getParamName());
        configSnapshot.put("strictLane", properties.isStrictLane());
        configSnapshot.put("fallbackToDefault", properties.isFallbackToDefault());
        configSnapshot.put("laneContinuity", properties.isLaneContinuity());
        configSnapshot.put("filterOrder", properties.getFilterOrder());
        configSnapshot.put("cacheTtl", properties.getCacheTtl() != null ? properties.getCacheTtl().toString() : "3s");
        configSnapshot.put("allowedTags", properties.getAllowedTags());
        configSnapshot.put("whitelistAction", properties.getWhitelistAction().name());
        overview.put("properties", configSnapshot);

        // 2. 内存活跃分流规则快照
        Map<String, Object> rulesSnapshot = new LinkedHashMap<>();
        List<TrafficRule> activeRules = ruleProvider != null ? ruleProvider.getActiveRules() : Collections.emptyList();
        rulesSnapshot.put("activeRulesCount", activeRules != null ? activeRules.size() : 0);
        rulesSnapshot.put("activeRules", activeRules != null ? activeRules : Collections.emptyList());

        if (ruleProvider instanceof NacosTrafficRuleListener nacosListener) {
            Map<String, Object> nacosSnapshot = new LinkedHashMap<>();
            nacosSnapshot.put("strategy", nacosListener.getStrategy() != null ? nacosListener.getStrategy().name() : "OVERWRITE");
            nacosSnapshot.put("preferApp", nacosListener.isPreferApp());
            nacosSnapshot.put("appRulesCount", nacosListener.getAppRules().size());
            nacosSnapshot.put("globalRulesCount", nacosListener.getGlobalRules().size());
            rulesSnapshot.put("nacos", nacosSnapshot);
        }
        overview.put("rules", rulesSnapshot);

        // 3. 核心监控度量统计快照
        if (metricsManager != null) {
            overview.put("metrics", metricsManager.getMetricsSummary());
        }

        return overview;
    }

    /**
     * POST /actuator/traffic/simulate 模拟匹配沙箱
     * <p>
     * 接收模拟的请求上下文（headers、queryParams、cookies、path、userId、tenantId、clientIp），
     * 返回预测匹配的规则与目标流量标签，计算耗时（纳秒），且 100% 杜绝污染真实线程上下文。
     *
     * @param action 动作指令（固定为 simulate）
     * @return 模拟评估结果字典
     */
    @WriteOperation
    public Map<String, Object> simulateWithAction(
            @Selector String action,
            @Nullable Object headers,
            @Nullable Object queryParams,
            @Nullable Object cookies,
            @Nullable String path,
            @Nullable String userId,
            @Nullable String tenantId,
            @Nullable String clientIp) {
        if (!"simulate".equalsIgnoreCase(action)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", "Unsupported action: '" + action + "'. Supported actions: 'simulate'");
            return err;
        }
        return doSimulate(headers, queryParams, cookies, path, userId, tenantId, clientIp);
    }

    /**
     * POST /actuator/traffic 模拟匹配沙箱 (支持直接向根端点 POST 发起模拟)
     */
    @WriteOperation
    public Map<String, Object> simulate(
            @Nullable Object headers,
            @Nullable Object queryParams,
            @Nullable Object cookies,
            @Nullable String path,
            @Nullable String userId,
            @Nullable String tenantId,
            @Nullable String clientIp) {
        return doSimulate(headers, queryParams, cookies, path, userId, tenantId, clientIp);
    }

    private Map<String, Object> doSimulate(
            Object headers,
            Object queryParams,
            Object cookies,
            String path,
            String userId,
            String tenantId,
            String clientIp) {
        long startTime = System.nanoTime();

        // 1. 构建无副作用的独立评估上下文
        SimpleTrafficEvaluationContext.Builder builder = SimpleTrafficEvaluationContext.builder();
        Map<String, String> headerMap = parseMap(headers);
        Map<String, String> queryParamMap = parseMap(queryParams);
        Map<String, String> cookieMap = parseMap(cookies);

        headerMap.forEach(builder::header);
        queryParamMap.forEach(builder::queryParam);
        cookieMap.forEach(builder::cookie);
        if (StringUtils.hasText(path)) {
            builder.path(path.trim());
        }
        if (StringUtils.hasText(userId)) {
            builder.userId(userId.trim());
        }
        if (StringUtils.hasText(tenantId)) {
            builder.tenantId(tenantId.trim());
        }
        if (StringUtils.hasText(clientIp)) {
            builder.clientIp(clientIp.trim());
        }
        SimpleTrafficEvaluationContext evalContext = builder.build();

        // 2. 提取当前生效的分流规则列表
        List<TrafficRule> activeRules = ruleProvider != null ? ruleProvider.getActiveRules() : Collections.emptyList();
        if (activeRules == null) {
            activeRules = Collections.emptyList();
        }

        // 3. 执行规则决策匹配
        TrafficRule matchedRule = ruleMatcher.matchRule(activeRules, evalContext);
        long durationNanos = System.nanoTime() - startTime;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("durationNanos", durationNanos);
        result.put("rulesEvaluatedCount", activeRules.size());

        if (matchedRule != null) {
            result.put("matched", true);
            result.put("matchedRuleId", matchedRule.getId());
            result.put("matchedRuleName", matchedRule.getName());
            result.put("priority", matchedRule.getPriority());
            result.put("targetTag", matchedRule.getTargetTag());

            // 条件命中详情
            Map<String, Object> hitCondition = new LinkedHashMap<>();
            hitCondition.put("relation", matchedRule.getConditionRelation() != null ? matchedRule.getConditionRelation().name() : "AND");
            List<Map<String, Object>> conditionsDetail = new ArrayList<>();
            if (matchedRule.getConditions() != null) {
                for (TrafficCondition cond : matchedRule.getConditions()) {
                    Map<String, Object> cd = new LinkedHashMap<>();
                    cd.put("source", cond.getFieldSource() != null ? cond.getFieldSource().name() : null);
                    cd.put("field", cond.getFieldName());
                    cd.put("operator", cond.getOperator() != null ? cond.getOperator().name() : "EXACT");
                    cd.put("expected", cond.getExpectedValue());
                    String actual = ruleMatcher.extractFieldValue(cond.getFieldSource(), cond.getFieldName(), evalContext);
                    cd.put("actual", actual);
                    boolean hit = predicateMatcher.matches(cond, actual);
                    cd.put("hit", hit);
                    conditionsDetail.add(cd);
                }
            }
            hitCondition.put("conditions", conditionsDetail);
            result.put("hitCondition", hitCondition);

            // 粘性哈希抽样详情
            String hashKey = ruleMatcher.extractHashKey(matchedRule, evalContext);
            if (hashKey != null) {
                int bucket = stickyHashPartitioner.partition(hashKey);
                result.put("hashKey", hashKey);
                result.put("hashBucket", bucket);
            }

            // 白名单状态预测
            Set<String> allowedTags = properties.getAllowedTags();
            if (allowedTags != null && !allowedTags.isEmpty()) {
                boolean allowed = allowedTags.contains(matchedRule.getTargetTag());
                result.put("whitelistAllowed", allowed);
                result.put("whitelistAction", properties.getWhitelistAction().name());
            } else {
                result.put("whitelistAllowed", true);
            }
        } else {
            result.put("matched", false);
            result.put("matchedRuleId", null);
            result.put("targetTag", null);
            result.put("hitCondition", null);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseMap(Object obj) {
        if (obj == null) {
            return Collections.emptyMap();
        }
        if (obj instanceof Map<?, ?> map) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue() != null ? String.valueOf(entry.getValue()) : null);
                }
            }
            return Collections.unmodifiableMap(result);
        }
        if (obj instanceof String str && StringUtils.hasText(str)) {
            String trimmed = str.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    Map<String, Object> rawMap = JsonUtil.parseObject(trimmed, Map.class);
                    if (rawMap != null) {
                        Map<String, String> result = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                            result.put(entry.getKey(), entry.getValue() != null ? String.valueOf(entry.getValue()) : null);
                        }
                        return Collections.unmodifiableMap(result);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return Collections.emptyMap();
    }
}
