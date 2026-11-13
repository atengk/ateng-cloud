package io.github.atengk.cloud.sentinel.parser;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AtengRequestOriginParser} 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengRequestOriginParserTest {

    private AtengSentinelProperties properties;
    private AtengRequestOriginParser parser;

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        this.properties = new AtengSentinelProperties();
        this.parser = new AtengRequestOriginParser(properties);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("微服务出站标头优先：存在 X-Origin-Service 时优先返回服务名")
    void testOriginServiceHeaderPriority() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.addHeader("X-Origin-Service", "order-service");
        request.addHeader("X-Gateway-Caller", "api-gateway");
        request.addHeader("X-Forwarded-For", "192.168.1.100");

        assertThat(parser.parseOrigin(request)).isEqualTo("order-service");
    }

    @Test
    @DisplayName("自定义服务标头配置：修改 originHeader / headerName 时按自定义标头解析")
    void testCustomOriginHeader() {
        properties.getOriginParser().setOriginHeader("X-Custom-Client");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.addHeader("X-Custom-Client", "payment-service");
        request.addHeader("X-Origin-Service", "ignore-me");

        assertThat(parser.parseOrigin(request)).isEqualTo("payment-service");
        assertThat(properties.getOriginParser().getHeaderName()).isEqualTo("X-Custom-Client");
    }

    @Test
    @DisplayName("网关调用源回溯：无微服务标头时回退到 gatewayHeader (默认 X-Gateway-Caller)")
    void testGatewayCallerFallback() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.addHeader("X-Gateway-Caller", "api-gateway");
        request.addHeader("X-Forwarded-For", "192.168.1.100");

        assertThat(parser.parseOrigin(request)).isEqualTo("api-gateway");
    }

    @Test
    @DisplayName("自定义网关标头：修改 gatewayHeader 时按自定义标头回溯")
    void testCustomGatewayHeader() {
        properties.getOriginParser().setGatewayHeader("X-My-Gateway");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.addHeader("X-My-Gateway", "edge-router");

        assertThat(parser.parseOrigin(request)).isEqualTo("edge-router");
    }

    @Test
    @DisplayName("客户端 IP 兜底：启用 IP 回退时，无服务标头回退到客户端真实 IP")
    void testClientIpFallbackEnabled() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.addHeader("X-Forwarded-For", "203.0.113.195, 10.0.0.1");

        assertThat(parser.parseOrigin(request)).isEqualTo("203.0.113.195");

        MockHttpServletRequest requestRealIp = new MockHttpServletRequest("GET", "/api/data");
        requestRealIp.addHeader("X-Real-IP", "198.51.100.5");
        assertThat(parser.parseOrigin(requestRealIp)).isEqualTo("198.51.100.5");

        MockHttpServletRequest requestRemote = new MockHttpServletRequest("GET", "/api/data");
        requestRemote.setRemoteAddr("127.0.0.1");
        assertThat(parser.parseOrigin(requestRemote)).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("禁用客户端 IP 兜底：无服务标头且关闭 IP 回退时直接返回 defaultOrigin")
    void testClientIpFallbackDisabled() {
        properties.getOriginParser().setEnableClientIpFallback(false);
        properties.getOriginParser().setDefaultOrigin("anonymous");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.addHeader("X-Forwarded-For", "203.0.113.195");
        request.setRemoteAddr("10.0.0.1");

        assertThat(parser.parseOrigin(request)).isEqualTo("anonymous");
    }

    @Test
    @DisplayName("默认来源保底：空请求或全空标头且无远端地址时返回配置的 defaultOrigin")
    void testDefaultFallback() {
        MockHttpServletRequest emptyRequest = new MockHttpServletRequest("GET", "/api/data");
        emptyRequest.setRemoteAddr(null);
        assertThat(parser.parseOrigin(emptyRequest)).isEqualTo("default");
        assertThat(parser.parseOrigin(null)).isEqualTo("default");

        properties.getOriginParser().setDefaultOrigin("unknown-caller");
        assertThat(parser.parseOrigin(emptyRequest)).isEqualTo("unknown-caller");
    }

    @Test
    @DisplayName("多租户上下文回退溯源：默认关闭，开启后在标头缺失时优先提取 TenantContextHolder 并附加 tenantPrefix")
    void testTenantContextFallback() {
        TenantContextHolder.setTenantId("1001");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.addHeader("X-Forwarded-For", "203.0.113.195");

        // 1. 默认关闭 enableTenantFallback，直接回退到客户端 IP
        assertThat(properties.getOriginParser().isEnableTenantFallback()).isFalse();
        assertThat(properties.getOriginParser().getTenantPrefix()).isEqualTo("tenant:");
        assertThat(parser.parseOrigin(request)).isEqualTo("203.0.113.195");

        // 2. 开启 enableTenantFallback，在标头缺失时返回 tenant:1001（优先于 IP）
        properties.getOriginParser().setEnableTenantFallback(true);
        assertThat(parser.parseOrigin(request)).isEqualTo("tenant:1001");

        // 3. 自定义 tenantPrefix
        properties.getOriginParser().setTenantPrefix("org-");
        assertThat(parser.parseOrigin(request)).isEqualTo("org-1001");

        // 4. 服务或网关标头优先级高于租户上下文
        request.addHeader("X-Gateway-Caller", "edge-gateway");
        assertThat(parser.parseOrigin(request)).isEqualTo("edge-gateway");

        // 5. 租户上下文为空时平滑继续回退到 IP
        TenantContextHolder.clear();
        MockHttpServletRequest noHeaderRequest = new MockHttpServletRequest("GET", "/api/data");
        noHeaderRequest.addHeader("X-Forwarded-For", "203.0.113.195");
        assertThat(parser.parseOrigin(noHeaderRequest)).isEqualTo("203.0.113.195");
    }
}
