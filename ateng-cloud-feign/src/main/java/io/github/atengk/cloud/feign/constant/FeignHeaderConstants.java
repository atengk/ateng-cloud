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
     * 内部微服务零信任受信凭据标头 (Same-Token)
     */
    public static final String SAME_TOKEN = "Same-Token";
}
