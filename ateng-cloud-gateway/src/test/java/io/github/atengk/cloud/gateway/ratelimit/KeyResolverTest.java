package io.github.atengk.cloud.gateway.ratelimit;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分布式限流 KeyResolver 单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("多维限流键解析器单元测试")
class KeyResolverTest {

    private final IpKeyResolver ipKeyResolver = new IpKeyResolver();
    private final UserKeyResolver userKeyResolver = new UserKeyResolver();
    private final ApiKeyResolver apiKeyResolver = new ApiKeyResolver();

    @Test
    @DisplayName("IpKeyResolver 优先从安全属性获取真实 IP")
    void testIpKeyResolverFromAttribute() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayConstants.ATTR_CLIENT_IP, "203.0.113.195");

        String resolved = ipKeyResolver.resolve(exchange).block();
        assertEquals("203.0.113.195", resolved);
    }

    @Test
    @DisplayName("IpKeyResolver 从 X-Real-IP 标头或 RemoteAddress 或保底回退")
    void testIpKeyResolverFallback() {
        MockServerHttpRequest requestWithHeader = MockServerHttpRequest.get("/test")
                .header(GatewayConstants.X_REAL_IP, "198.51.100.22")
                .build();
        MockServerWebExchange exchangeWithHeader = MockServerWebExchange.from(requestWithHeader);
        assertEquals("198.51.100.22", ipKeyResolver.resolve(exchangeWithHeader).block());

        MockServerHttpRequest requestRemote = MockServerHttpRequest.get("/test")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 12345))
                .build();
        MockServerWebExchange exchangeRemote = MockServerWebExchange.from(requestRemote);
        assertEquals("10.0.0.5", ipKeyResolver.resolve(exchangeRemote).block());

        MockServerHttpRequest requestEmpty = MockServerHttpRequest.get("/test").build();
        MockServerWebExchange exchangeEmpty = MockServerWebExchange.from(requestEmpty);
        assertEquals("127.0.0.1", ipKeyResolver.resolve(exchangeEmpty).block());
    }

    @Test
    @DisplayName("UserKeyResolver 严格仅从鉴权安全属性提取用户 ID，伪造入站标头零信任忽略并降级为 anonymous")
    void testUserKeyResolverAuthenticated() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayConstants.ATTR_USER_ID, "88888");

        assertEquals("user_88888", userKeyResolver.resolve(exchange).block());

        // 零信任防线：未通过鉴权（属性无 ATTR_USER_ID）时，即便入站携带伪造 X-User-Id 也坚决忽略
        MockServerHttpRequest requestHeader = MockServerHttpRequest.get("/test")
                .header(GatewayConstants.X_USER_ID, "99999")
                .build();
        MockServerWebExchange exchangeHeader = MockServerWebExchange.from(requestHeader);
        assertEquals("anonymous_127.0.0.1", userKeyResolver.resolve(exchangeHeader).block());
    }

    @Test
    @DisplayName("UserKeyResolver 未登录请求兜底为 anonymous_<IP>")
    void testUserKeyResolverAnonymous() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayConstants.ATTR_CLIENT_IP, "192.168.1.50");

        assertEquals("anonymous_192.168.1.50", userKeyResolver.resolve(exchange).block());

        MockServerHttpRequest requestEmpty = MockServerHttpRequest.get("/test").build();
        MockServerWebExchange exchangeEmpty = MockServerWebExchange.from(requestEmpty);
        assertEquals("anonymous_127.0.0.1", userKeyResolver.resolve(exchangeEmpty).block());
    }

    @Test
    @DisplayName("ApiKeyResolver 组合 Path 与真实 IP 双维度键")
    void testApiKeyResolver() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders/create").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayConstants.ATTR_CLIENT_IP, "114.114.114.114");

        assertEquals("/api/v1/orders/create_114.114.114.114", apiKeyResolver.resolve(exchange).block());
    }
}
