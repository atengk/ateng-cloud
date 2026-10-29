package io.github.atengk.cloud.traffic.rule.model;

/**
 * 流量规则多源合并策略枚举
 *
 * @author Ateng
 * @since 2026-10-29
 */
public enum TrafficRuleMergeStrategy {

    /**
     * 覆盖模式: 优先采用应用专属规则，存在专属规则即采用；若专属规则为空则平滑回退至全局规则
     */
    OVERWRITE,

    /**
     * 合并模式: 将应用专属规则与全局规则合并并统一按优先级排序
     */
    MERGE;

    /**
     * 安全解析规则合并策略，不匹配或为空时保底返回 OVERWRITE
     *
     * @param value 策略字符串
     * @return 对应的枚举实例
     */
    public static TrafficRuleMergeStrategy fromString(String value) {
        if (value == null || value.isBlank()) {
            return OVERWRITE;
        }
        for (TrafficRuleMergeStrategy strategy : values()) {
            if (strategy.name().equalsIgnoreCase(value.trim())) {
                return strategy;
            }
        }
        return OVERWRITE;
    }
}
