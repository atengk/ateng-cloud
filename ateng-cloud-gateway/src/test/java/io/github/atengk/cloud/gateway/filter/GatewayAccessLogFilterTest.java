package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayAccessLogProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 访问日志审计与慢请求度量过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewayAccessLogFilterTest {

    @Test
    @DisplayName("测试正常请求触发访问日志审计并放行")
    void testNormalAccessLog() {
        AtengGatewayAccessLogProperties props = new AtengGatewayAccessLogProperties();
        props.setEnabled(true);
        props.setSlowThreshold(Duration.ofMillis(500));

        GatewayAccessLogFilter filter = new GatewayAccessLogFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/user/list")
                .header(GatewayConstants.X_TRACE_ID, "trace-access-log-123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayConstants.ATTR_CLIENT_IP, "192.168.1.100");

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("测试排除路径（如 /actuator/**）直接跳过日志度量")
    void testExcludedPathBypassesLog() {
        AtengGatewayAccessLogProperties props = new AtengGatewayAccessLogProperties();
        props.setEnabled(true);

        GatewayAccessLogFilter filter = new GatewayAccessLogFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/health").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }
}
