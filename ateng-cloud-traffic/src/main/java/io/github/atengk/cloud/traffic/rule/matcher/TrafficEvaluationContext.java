package io.github.atengk.cloud.traffic.rule.matcher;

/**
 * 流量规则评估请求上下文抽象契约
 * <p>
 * 解耦底层传输协议，天然兼容 Servlet 阻塞容器与 WebFlux 响应式网关。
 *
 * @author Ateng
 * @since 2026-10-29
 */
public interface TrafficEvaluationContext {

    /**
     * 获取指定名称的 HTTP 标头
     *
     * @param name 标头名称
     * @return 标头值，不存在返回 null
     */
    String getHeader(String name);

    /**
     * 获取指定名称的 URL Query 查询参数
     *
     * @param name 参数名称
     * @return 参数值，不存在返回 null
     */
    String getQueryParam(String name);

    /**
     * 获取指定名称的 Cookie 值
     *
     * @param name Cookie 名称
     * @return Cookie 值，不存在返回 null
     */
    String getCookie(String name);

    /**
     * 获取 HTTP 请求路径 URI
     *
     * @return 请求路径，如 /api/v1/orders
     */
    String getPath();

    /**
     * 获取当前业务用户 ID (未显式注入时支持回退至 UserContextHolder)
     *
     * @return 用户唯一标识
     */
    String getUserId();

    /**
     * 获取当前业务租户 ID (未显式注入时支持回退至 TenantContextHolder)
     *
     * @return 租户标识
     */
    String getTenantId();

    /**
     * 获取客户端 IP 地址
     *
     * @return 客户端真实 IP
     */
    String getClientIp();
}
