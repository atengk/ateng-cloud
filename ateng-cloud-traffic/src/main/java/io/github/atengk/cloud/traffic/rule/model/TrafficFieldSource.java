package io.github.atengk.cloud.traffic.rule.model;

/**
 * 流量规则匹配字段来源枚举
 *
 * @author Ateng
 * @since 2026-10-29
 */
public enum TrafficFieldSource {

    /**
     * HTTP 请求标头 (Header)
     */
    HEADER,

    /**
     * URL Query 查询参数
     */
    QUERY,

    /**
     * HTTP Cookie 字段
     */
    COOKIE,

    /**
     * HTTP 请求路径 URI
     */
    PATH,

    /**
     * 当前登录用户 ID (优先取自请求上下文或 UserContextHolder)
     */
    USER_ID,

    /**
     * 当前租户 ID (优先取自请求上下文或 TenantContextHolder)
     */
    TENANT_ID,

    /**
     * 客户端 IP 地址
     */
    IP
}
