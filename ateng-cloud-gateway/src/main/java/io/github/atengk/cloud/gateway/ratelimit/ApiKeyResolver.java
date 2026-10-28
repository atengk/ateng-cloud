package io.github.atengk.cloud.gateway.ratelimit;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 基于接口路径与客户端 IP 双维度的分布式限流键解析器
 *
 * <p>微服务专属架构铁律落地：
 * <ul>
 *     <li><b>热点接口精准防刷</b>：按 {@code <Path>_<IP>} 双重聚合限流，单个高频 API 突增不波及全站；</li>
 *     <li><b>路径安全归一化</b>：直接依托经网关规范化后的安全路径构建限流键；</li>
 *     <li><b>无锁纯反应式</b>：完全基于响应式模型实现，零阻塞 Netty EventLoop。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class ApiKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        if (!StringUtils.hasText(path)) {
            path = "/";
        }

        // 解析真实 IP
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

        return Mono.just(path + "_" + ip);
    }
}
