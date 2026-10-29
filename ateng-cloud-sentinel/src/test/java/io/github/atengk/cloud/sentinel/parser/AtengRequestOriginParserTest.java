package io.github.atengk.cloud.sentinel.parser;

import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
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
        this.properties = new AtengSentinelProperties();
        this.parser = new AtengRequestOriginParser(properties);
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
    @DisplayName("自定义服务标头配置：修改 headerName 时按自定义标头解析")
    void testCustomHeaderName() {
        properties.getOriginParser().setHeaderName("X-Custom-Client");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.addHeader("X-Custom-Client", "payment-service");
        request.addHeader("X-Origin-Service", "ignore-me");

        assertThat(parser.parseOrigin(request)).isEqualTo("payment-service");
    }

    @Test
    @DisplayName("网关调用源回溯：无微服务标头时回退到 X-Gateway-Caller")
    void testGatewayCallerFallback() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.addHeader("X-Gateway-Caller", "api-gateway");
        request.addHeader("X-Forwarded-For", "192.168.1.100");

        assertThat(parser.parseOrigin(request)).isEqualTo("api-gateway");
    }

    @Test
    @DisplayName("客户端 IP 兜底：无服务标头时回退到客户端 IP")
    void testClientIpFallback() {
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
    @DisplayName("默认来源保底：空请求或全空标头且无远端地址时返回 default")
    void testDefaultFallback() {
        MockHttpServletRequest emptyRequest = new MockHttpServletRequest("GET", "/api/data");
        emptyRequest.setRemoteAddr(null);
        assertThat(parser.parseOrigin(emptyRequest)).isEqualTo("default");
        assertThat(parser.parseOrigin(null)).isEqualTo("default");
    }
}
