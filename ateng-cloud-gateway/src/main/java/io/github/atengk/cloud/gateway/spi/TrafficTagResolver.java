package io.github.atengk.cloud.gateway.spi;

import org.springframework.web.server.ServerWebExchange;

/**
 * 流量染色标签决议扩展点 SPI
 *
 * <p>支持基于登录态、租户 ID、请求头或动态业务规则自决进站流量染色标签。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@FunctionalInterface
public interface TrafficTagResolver {

    /**
     * 根据当前请求上下文决议流量染色标签
     *
     * @param exchange 响应式请求上下文
     * @return 流量染色标签，返回 null 或空表示无特殊染色标签（走基线流量）
     */
    String resolveTag(ServerWebExchange exchange);
}
