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

    @Test
    @DisplayName("测试开启 includeQuery 时携带敏感 Query 请求安全审计并放行")
    void testAccessLogWithIncludeQueryAndMasking() {
        AtengGatewayAccessLogProperties props = new AtengGatewayAccessLogProperties();
        props.setEnabled(true);
        props.setIncludeQuery(true);
        props.setMaskKeys(java.util.List.of("customSecret"));

        GatewayAccessLogFilter filter = new GatewayAccessLogFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/user/search?keyword=ateng&password=123456&customSecret=topSecret")
                .header(GatewayConstants.X_TRACE_ID, "trace-query-mask-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("测试包含非法 % 转义的畸形 Query 旁路隔离且绝不中断请求")
    void testAccessLogWithMalformedQueryNeverThrows() {
        AtengGatewayAccessLogProperties props = new AtengGatewayAccessLogProperties();
        props.setEnabled(true);
        props.setIncludeQuery(true);

        GatewayAccessLogFilter filter = new GatewayAccessLogFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?foo=%ZZ&bar=1")
                .header(GatewayConstants.X_TRACE_ID, "trace-malformed-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("测试慢请求且包含 Query 参数审计告警")
    void testAccessLogSlowRequestWithIncludeQuery() {
        AtengGatewayAccessLogProperties props = new AtengGatewayAccessLogProperties();
        props.setEnabled(true);
        props.setIncludeQuery(true);
        props.setSlowThreshold(Duration.ofMillis(1));

        GatewayAccessLogFilter filter = new GatewayAccessLogFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/slow/test?key=123")
                .header(GatewayConstants.X_TRACE_ID, "trace-slow-query-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignored) {
            }
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }
}
