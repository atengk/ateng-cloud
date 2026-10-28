package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityHeadersProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 等保合规安全响应头强化与 CORS 去重过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewaySecurityHeadersFilterTest {

    @Test
    @DisplayName("测试出站自动注入等保三级 OWASP 安全响应头")
    void testSecurityHeadersInjected() {
        AtengGatewaySecurityHeadersProperties props = new AtengGatewaySecurityHeadersProperties();
        props.setEnabled(true);
        GatewaySecurityHeadersFilter filter = new GatewaySecurityHeadersFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = ex -> Mono.empty();
        filter.filter(exchange, chain).block();

        // 触发 beforeCommit 提交动作
        exchange.getResponse().setComplete().block();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
        assertEquals("SAMEORIGIN", headers.getFirst("X-Frame-Options"));
        assertEquals("1; mode=block", headers.getFirst("X-XSS-Protection"));
        assertEquals("max-age=31536000; includeSubDomains", headers.getFirst("Strict-Transport-Security"));
        assertEquals("strict-origin-when-cross-origin", headers.getFirst("Referrer-Policy"));
    }

    @Test
    @DisplayName("测试出站 CORS 跨域标头去重（保留首项防止浏览器报冲突）")
    void testCorsHeadersDeduped() {
        AtengGatewaySecurityHeadersProperties props = new AtengGatewaySecurityHeadersProperties();
        props.setEnabled(true);
        GatewaySecurityHeadersFilter filter = new GatewaySecurityHeadersFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // 模拟下游服务注入了重复跨域标头
        exchange.getResponse().getHeaders().addAll(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, List.of("https://example.com", "https://another.com"));
        exchange.getResponse().getHeaders().addAll(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, List.of("true", "true"));

        WebFilterChain chain = ex -> Mono.empty();
        filter.filter(exchange, chain).block();
        exchange.getResponse().setComplete().block();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertEquals(1, headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN).size());
        assertEquals("https://example.com", headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        assertEquals(1, headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS).size());
        assertEquals("true", headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    @DisplayName("测试配置关闭时不出站注入安全标头")
    void testDisabledNoHeadersInjected() {
        AtengGatewaySecurityHeadersProperties props = new AtengGatewaySecurityHeadersProperties();
        props.setEnabled(false);
        GatewaySecurityHeadersFilter filter = new GatewaySecurityHeadersFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = ex -> Mono.empty();
        filter.filter(exchange, chain).block();
        exchange.getResponse().setComplete().block();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertNull(headers.getFirst("X-Content-Type-Options"));
        assertNull(headers.getFirst("X-Frame-Options"));
    }
}
