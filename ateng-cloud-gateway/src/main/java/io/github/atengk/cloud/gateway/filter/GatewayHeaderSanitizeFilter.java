package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 入站敏感标头物理剥离清洗全局过滤器
 *
 * <p>置于网关最外层边缘防御管道（{@link GatewayConstants#ORDER_HEADER_SANITIZE}），
 * 无条件物理剥离入站请求中的 {@code X-User-Id}、{@code X-Tenant-Id}、{@code X-Ignore-Tenant}、
 * {@code Same-Token} 等内部受信标头，彻底杜绝外部恶意客户端冒充内部受信任身份实施的越权穿透攻击。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewayHeaderSanitizeFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayHeaderSanitizeFilter.class);

    private final AtengGatewaySecurityProperties securityProperties;

    public GatewayHeaderSanitizeFilter(AtengGatewaySecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        List<String> headersToSanitize = securityProperties.getSanitizeHeaders();
        if (headersToSanitize == null || headersToSanitize.isEmpty()) {
            return chain.filter(exchange);
        }

        HttpHeaders currentHeaders = exchange.getRequest().getHeaders();
        boolean containsSensitiveHeader = false;
        for (String headerName : headersToSanitize) {
            if (currentHeaders.containsKey(headerName)) {
                containsSensitiveHeader = true;
                break;
            }
        }

        // 若无敏感标头直接放行，免去昂贵的 Request Mutate
        if (!containsSensitiveHeader) {
            return chain.filter(exchange);
        }

        // 物理剥离敏感标头
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(httpHeaders -> {
                    for (String headerName : headersToSanitize) {
                        if (httpHeaders.containsKey(headerName)) {
                            log.debug("网关边缘清洗：物理剥离入站敏感标头 [{}]", headerName);
                            httpHeaders.remove(headerName);
                        }
                    }
                })
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_HEADER_SANITIZE;
    }
}
