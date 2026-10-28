package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 限流 429 统一转译过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("限流 429 统一转译过滤器单元测试")
class GatewayRateLimitResponseFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayRateLimitResponseFilter filter = new GatewayRateLimitResponseFilter(objectMapper);

    @Test
    @DisplayName("当底层限流触发 429 状态并 setComplete 时自动转译为统一 Result JSON")
    void testRateLimitResponseFormattingOnSetComplete() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_TRACE_ID, "trace-429-test")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayConstants.ATTR_TRACE_ID, "trace-429-test");

        // 模拟下游 RequestRateLimiter 设置 429 并完成响应
        GatewayFilterChain chain = ex -> {
            ex.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return ex.getResponse().setComplete();
        };

        filter.filter(exchange, chain).block();

        MockServerHttpResponse response = exchange.getResponse();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().getContentType().toString());
        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals("trace-429-test", response.getHeaders().getFirst(GatewayConstants.X_TRACE_ID));

        String body = response.getBodyAsString().block();
        assertNotNull(body);
        JsonNode jsonNode = objectMapper.readTree(body);
        assertEquals(429, jsonNode.get("code").asInt());
        assertEquals("当前访问过于频繁，请稍后重试", jsonNode.get("msg").asText());
        assertEquals("trace-429-test", jsonNode.get("traceId").asText());
    }

    @Test
    @DisplayName("正常 200 请求不受影响直接放行")
    void testNormalResponseNotAffected() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            byte[] bytes = "normal-data".getBytes(StandardCharsets.UTF_8);
            return ex.getResponse().writeWith(Mono.just(ex.getResponse().bufferFactory().wrap(bytes)));
        };

        filter.filter(exchange, chain).block();

        MockServerHttpResponse response = exchange.getResponse();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("normal-data", response.getBodyAsString().block());
    }
}
