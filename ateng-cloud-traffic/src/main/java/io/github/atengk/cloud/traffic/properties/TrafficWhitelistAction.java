package io.github.atengk.cloud.traffic.properties;

/**
 * 非法与未授权流量染色标签白名单处置动作枚举
 *
 * @author Ateng
 * @since 2026-10-31
 */
public enum TrafficWhitelistAction {

    /**
     * 剥离清洗模式 (默认)：
     * 打印 Warn 审计日志，将未授权流量标签置空并平滑降级至基线实例处理，保障业务可用性。
     */
    STRIP,

    /**
     * 阻断模式：
     * 抛出受管业务异常 (4211 UNAUTHORIZED_TRAFFIC_TAG) 直接 Fail-Fast 阻断请求，满足金融级安全防伪防穿透诉求。
     */
    REJECT
}
