package io.github.atengk.cloud.gateway.util;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 客户端真实 IP 解析器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("真实客户端 IP 解析器单元测试")
class ClientIpResolverTest {

    @Test
    @DisplayName("trustedProxyCount=0 时直接返回物理对端 IP")
    void testUntrustedProxyHop() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 12345))
                .header(GatewayConstants.X_FORWARDED_FOR, "1.1.1.1, 2.2.2.2")
                .build();

        String clientIp = ClientIpResolver.resolveClientIp(request, 0);
        assertEquals("10.0.0.1", clientIp);
    }

    @Test
    @DisplayName("trustedProxyCount=1 时从右往左解析跳过 1 层前置代理")
    void testSingleTrustedProxyHop() {
        // 场景：恶意客户端注入伪造 XFF "1.1.1.1"，真实对端经前置 SLB 追加真实 IP "203.0.113.195"
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("10.0.0.2", 80))
                .header(GatewayConstants.X_FORWARDED_FOR, "1.1.1.1, 203.0.113.195")
                .build();

        String clientIp = ClientIpResolver.resolveClientIp(request, 1);
        // 跳数 1 应当解析出 SLB 追加的最后一个 IP (203.0.113.195)，而抛弃前面伪造的 1.1.1.1
        assertEquals("203.0.113.195", clientIp);
    }

    @Test
    @DisplayName("trustedProxyCount=2 时跳过 2 层前置代理 (CDN + SLB)")
    void testDoubleTrustedProxyHop() {
        // 链条: Client(203.0.113.50) -> CDN(198.51.100.1) -> SLB(10.0.0.2) -> Gateway
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("10.0.0.2", 80))
                .header(GatewayConstants.X_FORWARDED_FOR, "forged.ip, 203.0.113.50, 198.51.100.1")
                .build();

        String clientIp = ClientIpResolver.resolveClientIp(request, 2);
        // hops=3, trusted=2 -> index = 3 - 2 = 1 -> 203.0.113.50
        assertEquals("203.0.113.50", clientIp);
    }

    @Test
    @DisplayName("X-Forwarded-For 携带端口与带括号 IPv6 端口剥离测试")
    void testStripPort() {
        assertEquals("192.168.1.1", ClientIpResolver.stripPort("192.168.1.1:8080"));
        assertEquals("::1", ClientIpResolver.stripPort("[::1]:8080"));
        assertEquals("fe80::1", ClientIpResolver.stripPort("fe80::1"));
        assertEquals("10.0.0.1", ClientIpResolver.stripPort("10.0.0.1"));
    }

    @Test
    @DisplayName("X-Forwarded-For 为空时回退 X-Real-IP 测试")
    void testFallbackToXRealIp() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("10.0.0.2", 80))
                .header(GatewayConstants.X_REAL_IP, "203.0.113.99:9000")
                .build();

        String clientIp = ClientIpResolver.resolveClientIp(request, 1);
        assertEquals("203.0.113.99", clientIp);
    }

    @Test
    @DisplayName("所有标头均为空时回退直接对端 IP 或 127.0.0.1")
    void testFallbackToRemoteAddress() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("192.168.10.20", 54321))
                .build();

        String clientIp = ClientIpResolver.resolveClientIp(request, 1);
        assertEquals("192.168.10.20", clientIp);

        // 空请求
        String fallback = ClientIpResolver.resolveClientIp(null, 1);
        assertEquals("127.0.0.1", fallback);
    }
}
