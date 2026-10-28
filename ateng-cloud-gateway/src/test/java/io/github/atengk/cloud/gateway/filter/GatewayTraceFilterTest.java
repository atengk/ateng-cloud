package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应式全链路追踪过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("响应式 Trace 过滤器单元测试")
class GatewayTraceFilterTest {

    private final GatewayTraceFilter traceFilter = new GatewayTraceFilter();

    @Test
    @DisplayName("请求未携带 traceId 时自动生成并透传与回写")
    void testGenerateTraceIdWhenMissing() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            chainCalled.set(true);
            String traceIdAttr = ex.getAttribute(GatewayConstants.ATTR_TRACE_ID);
            assertNotNull(traceIdAttr);
            assertEquals(32, traceIdAttr.length());
            assertEquals(traceIdAttr, ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TRACE_ID));
            return Mono.empty();
        };

        traceFilter.filter(exchange, chain).block();

        assertTrue(chainCalled.get());
        String responseTraceId = exchange.getResponse().getHeaders().getFirst(GatewayConstants.X_TRACE_ID);
        assertNotNull(responseTraceId);
        assertEquals(32, responseTraceId.length());
    }

    @Test
    @DisplayName("请求携带 X-Trace-Id 时原样继承透传")
    void testInheritExistingTraceId() {
        String existingTraceId = "test-trace-id-123456";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_TRACE_ID, existingTraceId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            assertEquals(existingTraceId, ex.getAttribute(GatewayConstants.ATTR_TRACE_ID));
            assertEquals(existingTraceId, ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TRACE_ID));
            return Mono.empty();
        };

        traceFilter.filter(exchange, chain).block();
        assertEquals(existingTraceId, exchange.getResponse().getHeaders().getFirst(GatewayConstants.X_TRACE_ID));
    }

    @Test
    @DisplayName("请求携带 W3C traceparent 时正确解析 traceId")
    void testInheritW3cTraceparent() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("traceparent", traceparent)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ex.getAttribute(GatewayConstants.ATTR_TRACE_ID));
            return Mono.empty();
        };

        traceFilter.filter(exchange, chain).block();
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", exchange.getResponse().getHeaders().getFirst(GatewayConstants.X_TRACE_ID));
    }
}
