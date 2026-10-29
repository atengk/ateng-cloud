package io.github.atengk.cloud.gateway.resolver;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.spi.TrafficTagResolver;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

/**
 * 默认流量染色标签解析器实现
 *
 * <p>解析优先级：
 * 1. HTTP 请求头 {@code X-Traffic-Tag}（高优先级）；
 * 2. URL 查询参数 {@code traffic_tag}（低优先级）。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class DefaultTrafficTagResolver implements TrafficTagResolver {

    @Override
    public String resolveTag(ServerWebExchange exchange) {
        if (exchange == null || exchange.getRequest() == null) {
            return null;
        }

        // 1. 优先从 Header 提取
        String trafficTag = exchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TRAFFIC_TAG);
        if (StringUtils.hasText(trafficTag)) {
            return trafficTag.trim();
        }

        // 2. 降级从 Query 参数 traffic_tag 提取
        trafficTag = exchange.getRequest().getQueryParams().getFirst(GatewayConstants.QUERY_TRAFFIC_TAG);
        if (StringUtils.hasText(trafficTag)) {
            return trafficTag.trim();
        }

        return null;
    }
}
