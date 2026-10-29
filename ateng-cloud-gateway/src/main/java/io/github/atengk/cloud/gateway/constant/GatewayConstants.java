package io.github.atengk.cloud.gateway.constant;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import org.springframework.core.Ordered;

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
     * 挂载在 ServerWebExchange Attributes 中的当前登录用户 ID 键
     */
    public static final String ATTR_USER_ID = "ateng.gateway.userId";

    /**
     * 挂载在 ServerWebExchange Attributes 中的租户 ID 键
     */
    public static final String ATTR_TENANT_ID = "ateng.gateway.tenantId";

    /**
     * 挂载在 ServerWebExchange Attributes 中的流量染色标签键
     */
    public static final String ATTR_TRAFFIC_TAG = "ateng.gateway.trafficTag";

    /**
     * 挂载在 ServerWebExchange Attributes 中的请求入站开始时间戳 (毫秒)
     */
    public static final String ATTR_START_TIME = "ateng.gateway.startTime";

    // --- 动态路由配置属性前缀常量 ---

    /**
     * Spring Cloud Gateway 路由列表配置属性前缀
     */
    public static final String PREFIX_GATEWAY_ROUTES = "spring.cloud.gateway.routes";

    /**
     * Spring Cloud Gateway 全局默认过滤器配置属性前缀 (中划线命名)
     */
    public static final String PREFIX_GATEWAY_DEFAULT_FILTERS = "spring.cloud.gateway.default-filters";

    /**
     * Spring Cloud Gateway 全局默认过滤器配置属性前缀 (连写命名兼容)
     */
    public static final String PREFIX_GATEWAY_DEFAULT_FILTERS_COMPACT = "spring.cloud.gateway.defaultfilters";

    // --- 全局过滤器执行顺序基线 ---

    /**
     * OWASP 与等保三级出站安全响应头强化过滤器执行顺序 (最高优先级注册 beforeCommit 钩子)
     */
    public static final int ORDER_SECURITY_HEADERS = Ordered.HIGHEST_PRECEDENCE;

    /**
     * 路径规范化与目录穿越防卫过滤器执行顺序
     */
    public static final int ORDER_PATH_VALIDATION = Ordered.HIGHEST_PRECEDENCE + 1;

    /**
     * 响应式链路追踪 traceId 生成与透传过滤器执行顺序
     */
    public static final int ORDER_TRACE = Ordered.HIGHEST_PRECEDENCE + 1;

    /**
     * 外部入站敏感标头物理剥离清洗过滤器执行顺序
     */
    public static final int ORDER_HEADER_SANITIZE = Ordered.HIGHEST_PRECEDENCE + 2;

    /**
     * 真实公网客户端 IP 解析过滤器执行顺序
     */
    public static final int ORDER_CLIENT_IP = Ordered.HIGHEST_PRECEDENCE + 3;

    /**
     * IP 黑白名单防卫过滤器执行顺序
     */
    public static final int ORDER_IP_FILTER = Ordered.HIGHEST_PRECEDENCE + 4;

    /**
     * Sa-Token 反应式集中鉴权过滤器执行顺序
     */
    public static final int ORDER_SA_TOKEN = -100;

    /**
     * 多租户入站解析与防跨越过滤器执行顺序 (紧随鉴权之后)
     */
    public static final int ORDER_TENANT = -90;

    /**
     * 全链路灰度染色标签解析过滤器执行顺序 (置于集中鉴权与多租户解析之后，确保决议器可基于受信任身份与租户执行动态染色)
     */
    public static final int ORDER_TRAFFIC_TAG = ORDER_TENANT + 10;


    /**
     * 访问日志轻量审计与慢请求告警过滤器执行顺序
     */
    public static final int ORDER_ACCESS_LOG = Ordered.LOWEST_PRECEDENCE;
}
