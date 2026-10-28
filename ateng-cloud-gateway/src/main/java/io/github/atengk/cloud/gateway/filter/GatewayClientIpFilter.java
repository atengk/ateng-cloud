package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityProperties;
import io.github.atengk.cloud.gateway.util.ClientIpResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 真实公网客户端 IP 解析全局过滤器
 *
 * <p>置于网关入站防御管道（{@link GatewayConstants#ORDER_CLIENT_IP}），根据前置可信反向代理跳数
 * （{@code trusted-proxy-count}）自右向左安全倒推解析真实公网客户端 IP，并将权威 IP 注入
 * {@link ServerWebExchange#getAttributes()}（键名 {@link GatewayConstants#ATTR_CLIENT_IP}），
 * 同时覆写规范化下游 {@code X-Real-IP} 请求头，供后续限流、鉴权与审计消费。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewayClientIpFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayClientIpFilter.class);

    private final AtengGatewaySecurityProperties securityProperties;

    public GatewayClientIpFilter(AtengGatewaySecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        int trustedProxyCount = securityProperties != null ? securityProperties.getTrustedProxyCount() : 1;
        String clientIp = ClientIpResolver.resolveClientIp(exchange.getRequest(), trustedProxyCount);

        // 1. 将权威客户端真实 IP 绑定至当前 Exchange 上下文属性
        exchange.getAttributes().put(GatewayConstants.ATTR_CLIENT_IP, clientIp);

        // 2. 规范重注入 X-Real-IP 请求标头，向下游微服务透明透传权威真实 IP
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(GatewayConstants.X_REAL_IP, clientIp)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_CLIENT_IP;
    }
}
