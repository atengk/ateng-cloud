package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
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
 * 1. 优先解析 HTTP 请求头 {@code X-Traffic-Tag}，未携带时解析 URL 查询参数 {@code traffic_tag}；
 * 2. 将解析出的流量标签挂载至 {@link ServerWebExchange} Attributes ({@link GatewayConstants#ATTR_TRAFFIC_TAG})，供负载均衡器消费；
 * 3. 对下游请求执行 Request Header 染色透传，确保全链路泳道穿透。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewayTrafficTagFilter implements GlobalFilter, Ordered {

    private final AtengGatewayTrafficProperties properties;

    public GatewayTrafficTagFilter(AtengGatewayTrafficProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (properties == null || !properties.isEnabled()) {
            return chain.filter(exchange);
        }

        // 1. 优先从 Header 提取染色标签，若无则降级提取 Query 参数
        String trafficTag = exchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TRAFFIC_TAG);
        if (!StringUtils.hasText(trafficTag)) {
            trafficTag = exchange.getRequest().getQueryParams().getFirst(GatewayConstants.QUERY_TRAFFIC_TAG);
        }

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
