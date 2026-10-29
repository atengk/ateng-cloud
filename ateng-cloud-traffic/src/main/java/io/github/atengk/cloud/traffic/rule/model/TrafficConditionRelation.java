package io.github.atengk.cloud.traffic.rule.model;

/**
 * 流量规则多条件逻辑关系枚举
 *
 * @author Ateng
 * @since 2026-10-29
 */
public enum TrafficConditionRelation {

    /**
     * 且关系 (所有条件必须同时满足)
     */
    AND,

    /**
     * 或关系 (满足任意一个条件即判定命中)
     */
    OR
}
