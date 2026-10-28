package io.github.atengk.cloud.gateway.ratelimit;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 基于 Sa-Token 登录用户维度的分布式限流键解析器
 *
 * <p>微服务专属架构铁律落地：
 * <ul>
 *     <li><b>受信用户身份对齐</b>：优先提取网关鉴权过滤器解析校验后的真实 {@code userId}；</li>
 *     <li><b>匿名请求弹性兜底</b>：未登录或匿名接口请求自动降级为 {@code anonymous_<ip>}，杜绝空键导致限流穿透；</li>
 *     <li><b>无锁纯反应式</b>：非阻塞返回 {@link Mono}，适配 Netty 异步事件模型。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class UserKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        // 1. 优先提取鉴权通过后注入的 Exchange 属性
        String userId = exchange.getAttribute(GatewayConstants.ATTR_USER_ID);
        if (!StringUtils.hasText(userId)) {
            // 2. 探测请求头中的 X-User-Id
            userId = exchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID);
        }

        if (StringUtils.hasText(userId)) {
            return Mono.just("user_" + userId);
        }

        // 3. 未登录或白名单接口，兜底使用客户端真实 IP 限流，防止匿名刷接口打崩服务
        String ip = exchange.getAttribute(GatewayConstants.ATTR_CLIENT_IP);
        if (!StringUtils.hasText(ip)) {
            ip = exchange.getRequest().getHeaders().getFirst(GatewayConstants.X_REAL_IP);
        }
        if (!StringUtils.hasText(ip) && exchange.getRequest().getRemoteAddress() != null) {
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            if (remoteAddress.getAddress() != null) {
                ip = remoteAddress.getAddress().getHostAddress();
            }
        }
        if (!StringUtils.hasText(ip)) {
            ip = "127.0.0.1";
        }

        return Mono.just("anonymous_" + ip);
    }
}
