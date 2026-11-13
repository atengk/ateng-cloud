package io.github.atengk.cloud.traffic.rule.model;

/**
 * 流量规则多维谓词匹配操作符枚举
 *
 * @author Ateng
 * @since 2026-10-29
 */
public enum TrafficMatchOperator {

    /**
     * 精确匹配 (等于指定值)
     */
    EXACT,

    /**
     * 反向精确匹配 (不等于指定值；当实际值为 null 且期望值非 null 时判定为 true)
     */
    NOT_EXACT,

    /**
     * 集合包含匹配 (实际值在指定集合或逗号分隔列表中)
     */
    IN,

    /**
     * 反向集合排除匹配 (实际值不在指定集合或逗号分隔列表中；当实际值为 null 时判定为 true)
     */
    NOT_IN,

    /**
     * 前缀匹配 (实际值以指定字符串开头)
     */
    PREFIX,

    /**
     * 后缀匹配 (实际值以指定字符串结尾)
     */
    SUFFIX,

    /**
     * 包含匹配 (实际值包含指定子串)
     */
    CONTAINS,

    /**
     * 正则表达式匹配 (实际值匹配指定正则规则)
     */
    REGEX
}
