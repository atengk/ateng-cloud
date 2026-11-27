package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayCryptoProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityHeadersProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
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

    @Test
    @DisplayName("跨域生效时将已写出的签名与加解密响应头合并至 Access-Control-Expose-Headers（大小写去重）")
    void testMergeSecurityExposeHeadersWhenCorsActive() {
        AtengGatewaySecurityHeadersProperties secProps = new AtengGatewaySecurityHeadersProperties();
        AtengGatewaySignProperties signProps = new AtengGatewaySignProperties();
        AtengGatewayCryptoProperties cryptoProps = new AtengGatewayCryptoProperties();
        GatewaySecurityHeadersFilter filter = new GatewaySecurityHeadersFilter(secProps, signProps, cryptoProps);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/crypto/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        HttpHeaders respHeaders = exchange.getResponse().getHeaders();
        respHeaders.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://spa.example.com");
        respHeaders.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "x-signature, X-Custom-Id");
        respHeaders.set(GatewayConstants.X_SIGNATURE, "sig-val");
        respHeaders.set(GatewayConstants.X_TIMESTAMP, "1700000000000");
        respHeaders.set(GatewayConstants.X_ENCRYPT_IV, "iv-val");
        respHeaders.set(GatewayConstants.X_KEY_ID, "v1");

        filter.filter(exchange, ex -> Mono.empty()).block();
        exchange.getResponse().setComplete().block();

        String exposed = respHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
        assertTrue(exposed.contains("x-signature"));
        assertTrue(exposed.contains("X-Custom-Id"));
        assertTrue(exposed.contains(GatewayConstants.X_TIMESTAMP));
        assertTrue(exposed.contains(GatewayConstants.X_ENCRYPT_IV));
        assertTrue(exposed.contains(GatewayConstants.X_KEY_ID));
        assertFalse(exposed.contains(GatewayConstants.X_NONCE));
        assertFalse(exposed.contains(GatewayConstants.X_ENCRYPT_KEY));
        assertEquals(1, java.util.Arrays.stream(exposed.split(","))
                .map(String::trim)
                .filter(s -> s.equalsIgnoreCase(GatewayConstants.X_SIGNATURE))
                .count());
    }

    @Test
    @DisplayName("CORS 已配置通配符 Expose-Headers 或未激活 CORS 时不追加 Expose-Headers")
    void testSkipExposeMergeWhenWildcardOrNoCors() {
        GatewaySecurityHeadersFilter filter = new GatewaySecurityHeadersFilter(
                new AtengGatewaySecurityHeadersProperties(),
                new AtengGatewaySignProperties(),
                new AtengGatewayCryptoProperties());

        MockServerWebExchange noCorsExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test").build());
        noCorsExchange.getResponse().getHeaders().set(GatewayConstants.X_ENCRYPT_IV, "iv-1");
        filter.filter(noCorsExchange, ex -> Mono.empty()).block();
        noCorsExchange.getResponse().setComplete().block();
        assertNull(noCorsExchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS));

        MockServerWebExchange wildcardExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test").build());
        wildcardExchange.getResponse().getHeaders().set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://a.com");
        wildcardExchange.getResponse().getHeaders().set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "*");
        wildcardExchange.getResponse().getHeaders().set(GatewayConstants.X_ENCRYPT_IV, "iv-1");
        filter.filter(wildcardExchange, ex -> Mono.empty()).block();
        wildcardExchange.getResponse().setComplete().block();
        assertEquals("*", wildcardExchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS));
    }

    @Test
    @DisplayName("CORS 同时合并签名头、加解密头与自定义标头名称")
    void testMergeCustomAndFullSignCryptoHeaders() {
        AtengGatewaySecurityHeadersProperties secProps = new AtengGatewaySecurityHeadersProperties();
        AtengGatewaySignProperties signProps = new AtengGatewaySignProperties();
        signProps.setHeaderSignature("X-Custom-Sig");
        AtengGatewayCryptoProperties cryptoProps = new AtengGatewayCryptoProperties();
        cryptoProps.setHeaderEncryptKey("X-Custom-Key");
        GatewaySecurityHeadersFilter filter = new GatewaySecurityHeadersFilter(secProps, signProps, cryptoProps);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/crypto/test").build());
        HttpHeaders respHeaders = exchange.getResponse().getHeaders();
        respHeaders.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.example.com");
        respHeaders.set("X-Custom-Sig", "sig");
        respHeaders.set(GatewayConstants.X_TIMESTAMP, "1700000000");
        respHeaders.set(GatewayConstants.X_NONCE, "n-1");
        respHeaders.set("X-Custom-Key", "ek");
        respHeaders.set(GatewayConstants.X_ENCRYPT_IV, "iv");
        respHeaders.set(GatewayConstants.X_KEY_ID, "v1");
        respHeaders.set(GatewayConstants.X_ENCRYPT_ALG, "RSA_AES_GCM");

        filter.filter(exchange, ex -> Mono.empty()).block();
        exchange.getResponse().setComplete().block();

        String exposed = respHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
        assertTrue(exposed.contains("X-Custom-Sig"));
        assertTrue(exposed.contains(GatewayConstants.X_TIMESTAMP));
        assertTrue(exposed.contains(GatewayConstants.X_NONCE));
        assertTrue(exposed.contains("X-Custom-Key"));
        assertTrue(exposed.contains(GatewayConstants.X_ENCRYPT_IV));
        assertTrue(exposed.contains(GatewayConstants.X_KEY_ID));
        assertTrue(exposed.contains(GatewayConstants.X_ENCRYPT_ALG));
    }
}
