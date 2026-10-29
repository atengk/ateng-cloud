package io.github.atengk.cloud.traffic.rule.matcher;

import io.github.atengk.cloud.traffic.rule.hash.StickyHashPartitioner;
import io.github.atengk.cloud.traffic.rule.model.TrafficCondition;
import io.github.atengk.cloud.traffic.rule.model.TrafficConditionRelation;
import io.github.atengk.cloud.traffic.rule.model.TrafficFieldSource;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 纳秒级高性能全链路流量染色与金丝雀灰度规则决策引擎
 * <p>
 * 支持多维谓词（Header/Query/Cookie/Path/UserId/TenantId/IP）快速失败判定，
 * 以及基于 MurmurHash3 一致性哈希的百分比粘性切流。
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficRuleMatcher {

    private static final Logger log = LoggerFactory.getLogger(TrafficRuleMatcher.class);

    private final StickyHashPartitioner stickyHashPartitioner;
    private final TrafficPredicateMatcher predicateMatcher;

    public TrafficRuleMatcher() {
        this(new StickyHashPartitioner(), new TrafficPredicateMatcher());
    }

    public TrafficRuleMatcher(StickyHashPartitioner stickyHashPartitioner, TrafficPredicateMatcher predicateMatcher) {
        this.stickyHashPartitioner = stickyHashPartitioner != null ? stickyHashPartitioner : new StickyHashPartitioner();
        this.predicateMatcher = predicateMatcher != null ? predicateMatcher : new TrafficPredicateMatcher();
    }

    /**
     * 评估规则列表并返回首个命中的流量染色标签
     *
     * @param rules   规则列表
     * @param context 评估上下文
     * @return 命中的流量染色标签（如 "gray", "v2"），未命中返回 null
     */
    public String matchTag(List<TrafficRule> rules, TrafficEvaluationContext context) {
        TrafficRule matchedRule = matchRule(rules, context);
        return matchedRule != null ? matchedRule.getTargetTag() : null;
    }

    /**
     * 评估规则列表并返回首个命中的流量规则
     *
     * @param rules   规则列表
     * @param context 评估上下文
     * @return 命中的流量规则，未命中返回 null
     */
    public TrafficRule matchRule(List<TrafficRule> rules, TrafficEvaluationContext context) {
        if (rules == null || rules.isEmpty() || context == null) {
            return null;
        }

        // 提取已启用规则并按 priority 升序排序 (数值越小优先级越高)
        List<TrafficRule> activeRules = new ArrayList<>(rules.size());
        for (TrafficRule rule : rules) {
            if (rule != null && rule.isEnabled()) {
                activeRules.add(rule);
            }
        }

        if (activeRules.isEmpty()) {
            return null;
        }

        if (activeRules.size() > 1) {
            activeRules.sort(Comparator.comparingInt(TrafficRule::getPriority));
        }

        for (TrafficRule rule : activeRules) {
            if (matches(rule, context)) {
                log.debug("[TrafficRuleMatcher] 请求命中流量灰度规则 [ruleId='{}', name='{}', priority={}, targetTag='{}']",
                        rule.getId(), rule.getName(), rule.getPriority(), rule.getTargetTag());
                return rule;
            }
        }

        return null;
    }

    /**
     * 判定单条规则是否整体命中（条件全满足且百分比放量命中）
     *
     * @param rule    流量规则
     * @param context 评估上下文
     * @return true 表示命中
     */
    public boolean matches(TrafficRule rule, TrafficEvaluationContext context) {
        if (rule == null || !rule.isEnabled() || context == null) {
            return false;
        }

        // 1. 评估条件集合
        if (!matchesConditions(rule, context)) {
            return false;
        }

        // 2. 评估一致性哈希百分比切流
        return matchesPercentage(rule, context);
    }

    /**
     * 判定规则的多维条件是否匹配
     *
     * @param rule    流量规则
     * @param context 评估上下文
     * @return true 表示条件匹配
     */
    public boolean matchesConditions(TrafficRule rule, TrafficEvaluationContext context) {
        List<TrafficCondition> conditions = rule.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }

        TrafficConditionRelation relation = rule.getConditionRelation();
        if (relation == null) {
            relation = TrafficConditionRelation.AND;
        }

        if (relation == TrafficConditionRelation.AND) {
            for (TrafficCondition condition : conditions) {
                if (!matchCondition(condition, context)) {
                    return false;
                }
            }
            return true;
        } else {
            for (TrafficCondition condition : conditions) {
                if (matchCondition(condition, context)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 判定单个条件是否匹配
     *
     * @param condition 匹配条件
     * @param context   评估上下文
     * @return true 表示满足条件
     */
    public boolean matchCondition(TrafficCondition condition, TrafficEvaluationContext context) {
        if (condition == null) {
            return true;
        }
        String actualValue = extractFieldValue(condition.getFieldSource(), condition.getFieldName(), context);
        return predicateMatcher.matches(condition, actualValue);
    }

    /**
     * 判定规则的百分比粘性抽样是否命中
     *
     * @param rule    流量规则
     * @param context 评估上下文
     * @return true 表示命中放量
     */
    public boolean matchesPercentage(TrafficRule rule, TrafficEvaluationContext context) {
        Integer percentage = rule.getPercentage();
        if (percentage == null || percentage >= 100) {
            return true;
        }
        if (percentage <= 0) {
            return false;
        }

        String hashKey = extractHashKey(rule, context);
        if (hashKey == null || hashKey.isBlank()) {
            log.debug("[TrafficRuleMatcher] 规则要求百分比切流但无法从上下文中提取哈希键，未命中放量 [ruleId='{}', source={}]",
                    rule.getId(), rule.getHashKeySource());
            return false;
        }

        return stickyHashPartitioner.isHit(hashKey, percentage);
    }

    /**
     * 根据来源与字段名称从评估上下文中提取实际值
     *
     * @param source    字段来源
     * @param fieldName 字段名称 (仅对 HEADER, QUERY, COOKIE 必选)
     * @param context   评估上下文
     * @return 实际值
     */
    public String extractFieldValue(TrafficFieldSource source, String fieldName, TrafficEvaluationContext context) {
        if (source == null) {
            source = TrafficFieldSource.HEADER;
        }
        return switch (source) {
            case HEADER -> context.getHeader(fieldName);
            case QUERY -> context.getQueryParam(fieldName);
            case COOKIE -> context.getCookie(fieldName);
            case PATH -> context.getPath();
            case USER_ID -> context.getUserId();
            case TENANT_ID -> context.getTenantId();
            case IP -> context.getClientIp();
        };
    }

    /**
     * 提取用于一致性哈希计算的种子键
     *
     * @param rule    流量规则
     * @param context 评估上下文
     * @return 哈希种子键
     */
    public String extractHashKey(TrafficRule rule, TrafficEvaluationContext context) {
        TrafficFieldSource source = rule.getHashKeySource();
        if (source == null) {
            source = TrafficFieldSource.USER_ID;
        }
        return extractFieldValue(source, rule.getHashKeyName(), context);
    }

    public StickyHashPartitioner getStickyHashPartitioner() {
        return stickyHashPartitioner;
    }

    public TrafficPredicateMatcher getPredicateMatcher() {
        return predicateMatcher;
    }
}
