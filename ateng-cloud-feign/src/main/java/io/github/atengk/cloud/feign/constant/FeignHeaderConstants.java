package io.github.atengk.cloud.feign.constant;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;

/**
 * Feign HTTP 通信专用上下文与网关防穿透标头常量
 *
 * @author Ateng
 * @since 2026-10-28
 */
public final class FeignHeaderConstants {

    private FeignHeaderConstants() {
    }

    /**
     * 分布式调用链链路追踪 Trace ID 标头
     */
    public static final String TRACE_ID = MessageHeaderConstants.TRACE_ID;

    /**
     * 当前登录用户唯一标识标头
     */
    public static final String USER_ID = MessageHeaderConstants.USER_ID;

    /**
     * 当前多租户隔离唯一标识标头
     */
    public static final String TENANT_ID = MessageHeaderConstants.TENANT_ID;

    /**
     * 忽略租户隔离过滤逻辑标记标头
     */
    public static final String IGNORE_TENANT = MessageHeaderConstants.IGNORE_TENANT;

    /**
     * 金丝雀灰度流量染色路由标记标头
     */
    public static final String TRAFFIC_TAG = "X-Traffic-Tag";

    /**
     * 流量所属灰度泳道名称标头
     */
    public static final String TRAFFIC_LANE = "X-Traffic-Lane";

    /**
     * 命中的动态流量分流规则标识标头
     */
    public static final String TRAFFIC_RULE = "X-Traffic-Rule";

    /**
     * Seata 全局分布式事务 XID 标头
     */
    public static final String TX_XID = "TX_XID";

    /**
     * 内部微服务零信任受信凭据标头 (Same-Token)
     */
    public static final String SAME_TOKEN = "Same-Token";

    /**
     * Sa-Token 兼容受信凭据别名标头
     */
    public static final String SA_SAME_TOKEN = "SA-SAME-TOKEN";

    /**
     * SLF4J MDC 全链路追踪键名
     */
    public static final String MDC_TRACE_ID = "traceId";

    /**
     * SLF4J MDC 分布式事务 XID 键名
     */
    public static final String MDC_XID = "xid";

    /**
     * SLF4J MDC 流量染色标签驼峰兼容键名
     */
    public static final String MDC_TRAFFIC_TAG = "trafficTag";

    /**
     * SLF4J MDC 流量染色标签连字符兼容键名
     */
    public static final String MDC_TRAFFIC_TAG_KEBAB = "traffic-tag";

    /**
     * SLF4J MDC 流量泳道驼峰兼容键名
     */
    public static final String MDC_TRAFFIC_LANE = "trafficLane";

    /**
     * SLF4J MDC 流量分流规则驼峰兼容键名
     */
    public static final String MDC_TRAFFIC_RULE = "trafficRule";
}
