package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
import io.github.atengk.cloud.gateway.spi.TrafficTagResolver;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 全链路流量染色与金丝雀灰度标签解析全局过滤器
 *
 * <p>核心治理语义：
 * 1. 依托可插拔 {@link TrafficTagResolver} SPI 扩展点决议请求流量染色标签；
 * 2. 将解析出的流量标签挂载至 {@link ServerWebExchange} Attributes ({@link GatewayConstants#ATTR_TRAFFIC_TAG})，供负载均衡器消费；
 * 3. 对下游请求执行 Request Header 染色透传，确保全链路泳道穿透。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewayTrafficTagFilter implements GlobalFilter, Ordered {

    private final AtengGatewayTrafficProperties properties;
    private final TrafficTagResolver trafficTagResolver;

    public GatewayTrafficTagFilter(AtengGatewayTrafficProperties properties) {
        this(properties, new io.github.atengk.cloud.gateway.resolver.DefaultTrafficTagResolver());
    }

    public GatewayTrafficTagFilter(AtengGatewayTrafficProperties properties,
                                   TrafficTagResolver trafficTagResolver) {
        this.properties = properties;
        this.trafficTagResolver = trafficTagResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (properties == null || !properties.isEnabled()) {
            return chain.filter(exchange);
        }

        // 1. 依托 TrafficTagResolver SPI 决议流量染色标签
        String trafficTag = trafficTagResolver != null ? trafficTagResolver.resolveTag(exchange) : null;

        // 2. 若存在有效标签，挂载至上下文并向下游透传
        if (StringUtils.hasText(trafficTag)) {
            String trimmedTag = trafficTag.trim();
            exchange.getAttributes().put(GatewayConstants.ATTR_TRAFFIC_TAG, trimmedTag);

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header(GatewayConstants.X_TRAFFIC_TAG, trimmedTag)
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_TRAFFIC_TAG;
    }
}
