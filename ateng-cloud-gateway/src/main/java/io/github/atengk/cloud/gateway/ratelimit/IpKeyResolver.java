package io.github.atengk.cloud.gateway.ratelimit;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 基于客户端真实公网 IP 的分布式限流键解析器
 *
 * <p>微服务专属架构铁律落地：
 * <ul>
 *     <li><b>可信真实 IP 解析</b>：优先复用网关安全清洗过滤器注入的受管属性 {@link GatewayConstants#ATTR_CLIENT_IP}；</li>
 *     <li><b>反向代理防欺诈兜底</b>：依次探测属性、{@code X-Real-IP}、{@code remoteAddress}，保底回退 {@code 127.0.0.1}；</li>
 *     <li><b>无锁纯反应式</b>：非阻塞返回 {@link Mono}，完全适配 Netty EventLoop。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class IpKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        // 1. 优先从 GatewayClientIpFilter 解析出的安全属性获取
        String ip = exchange.getAttribute(GatewayConstants.ATTR_CLIENT_IP);
        if (StringUtils.hasText(ip)) {
            return Mono.just(ip);
        }

        // 2. 探测已清洗的 X-Real-IP 标头
        ip = exchange.getRequest().getHeaders().getFirst(GatewayConstants.X_REAL_IP);
        if (StringUtils.hasText(ip)) {
            return Mono.just(ip);
        }

        // 3. 从底层 TCP 连接远端地址获取
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            String host = remoteAddress.getAddress().getHostAddress();
            if (StringUtils.hasText(host)) {
                return Mono.just(host);
            }
        }

        // 4. 保底默认地址
        return Mono.just("127.0.0.1");
    }
}
