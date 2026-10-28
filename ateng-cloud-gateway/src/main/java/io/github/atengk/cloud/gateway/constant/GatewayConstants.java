package io.github.atengk.cloud.gateway.constant;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;

/**
 * 网关全局核心常量定义
 *
 * @author Ateng
 * @since 2026-10-28
 */
public final class GatewayConstants {

    private GatewayConstants() {
    }

    /**
     * 链路追踪标识标头
     */
    public static final String X_TRACE_ID = MessageHeaderConstants.TRACE_ID;

    /**
     * 当前登录用户 ID 标头
     */
    public static final String X_USER_ID = MessageHeaderConstants.USER_ID;

    /**
     * 行级多租户隔离标识标头
     */
    public static final String X_TENANT_ID = MessageHeaderConstants.TENANT_ID;

    /**
     * 多租户逻辑短路标记标头
     */
    public static final String X_IGNORE_TENANT = MessageHeaderConstants.IGNORE_TENANT;

    /**
     * 全链路灰度染色与泳道标记标头
     */
    public static final String X_TRAFFIC_TAG = "X-Traffic-Tag";

    /**
     * 流量染色 URL 查询参数名
     */
    public static final String QUERY_TRAFFIC_TAG = "traffic_tag";

    /**
     * Sa-Token 内部同源数字签名受信凭证标头
     */
    public static final String SAME_TOKEN = "Same-Token";

    /**
     * Sa-Token 内部同源凭证备选大写标头
     */
    public static final String SAME_TOKEN_UPPER = "SAME_TOKEN";

    /**
     * 标准 X-Forwarded-For 代理链标头
     */
    public static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /**
     * 阿里云/特定 SLB 原始客户端标头
     */
    public static final String X_ORIGINAL_FORWARDED_FOR = "X-Original-Forwarded-For";

    /**
     * 标准客户端真实 IP 标头
     */
    public static final String X_REAL_IP = "X-Real-IP";

    /**
     * API 签名防篡改时间戳标头
     */
    public static final String X_TIMESTAMP = "X-Timestamp";

    /**
     * API 签名防重放 Nonce 随机数字符串标头
     */
    public static final String X_NONCE = "X-Nonce";

    /**
     * API 签名摘要结果标头
     */
    public static final String X_SIGNATURE = "X-Signature";

    /**
     * API 签名 AppKey 标识标头
     */
    public static final String X_APP_ID = "X-App-Id";

    // --- Exchange Attributes 键名 ---

    /**
     * 挂载在 ServerWebExchange Attributes 中的 traceId 键
     */
    public static final String ATTR_TRACE_ID = "ateng.gateway.traceId";

    /**
     * 挂载在 ServerWebExchange Attributes 中的真实客户端 IP 键
     */
    public static final String ATTR_CLIENT_IP = "ateng.gateway.clientIp";

    /**
     * 挂载在 ServerWebExchange Attributes 中的流量染色标签键
     */
    public static final String ATTR_TRAFFIC_TAG = "ateng.gateway.trafficTag";

    /**
     * 挂载在 ServerWebExchange Attributes 中的请求入站开始时间戳 (毫秒)
     */
    public static final String ATTR_START_TIME = "ateng.gateway.startTime";
}
