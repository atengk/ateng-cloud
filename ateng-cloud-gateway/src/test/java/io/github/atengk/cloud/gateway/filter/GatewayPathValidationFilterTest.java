package io.github.atengk.cloud.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * URI 规范化与目录穿越防卫过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("URI 规范化与目录穿越防卫过滤器单元测试")
class GatewayPathValidationFilterTest {

    private final GatewayPathValidationFilter filter = new GatewayPathValidationFilter();

    @Test
    @DisplayName("正常合法路径正常放行")
    void testNormalPathAllowed() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            assertEquals("/api/v1/users", ex.getRequest().getURI().getRawPath());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
    }

    @Test
    @DisplayName("双点号目录穿越直接抛出 HTTP 400")
    void testDotDotBlocked() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/../secret").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = ex -> Mono.empty();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> filter.filter(exchange, chain).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("十六进制编码点号 (%2e%2e) 目录穿越直接抛出 HTTP 400")
    void testEncodedDotDotBlocked() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/%2e%2e/secret").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = ex -> Mono.empty();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> filter.filter(exchange, chain).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("空字节 (%00 或 \\0) 直接抛出 HTTP 400")
    void testNullByteBlocked() {
        MockServerHttpRequest request = MockServerHttpRequest.method(
                org.springframework.http.HttpMethod.GET,
                java.net.URI.create("http://localhost/api/test%00evil")
        ).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = ex -> Mono.empty();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> filter.filter(exchange, chain).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("包含 %0A 等非空字节的合法编码正常放行 (防误杀)")
    void testPercentZeroNonTraversalAllowed() {
        MockServerHttpRequest request = MockServerHttpRequest.method(
                org.springframework.http.HttpMethod.GET,
                java.net.URI.create("http://localhost/api/test%0Aline")
        ).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            assertEquals("/api/test%0Aline", ex.getRequest().getURI().getRawPath());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
    }

    @Test
    @DisplayName("多重连续斜杠自动规范化折叠为单斜杠")
    void testMultiSlashNormalized() {
        MockServerHttpRequest request = MockServerHttpRequest.get("///api///v1////users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            assertEquals("/api/v1/users", ex.getRequest().getURI().getRawPath());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
    }
}
