package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
import io.github.atengk.cloud.gateway.spi.TrafficTagResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全链路灰度染色标签解析过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewayTrafficTagFilterTest {

    @Test
    @DisplayName("测试从 HTTP 请求头解析灰度染色标签并挂载透传")
    void testExtractTagFromHeader() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(true);
        GatewayTrafficTagFilter filter = new GatewayTrafficTagFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_TRAFFIC_TAG, "gray-v2")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            assertEquals("gray-v2", ex.getAttribute(GatewayConstants.ATTR_TRAFFIC_TAG));
            assertEquals("gray-v2", ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TRAFFIC_TAG));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("测试从 URL Query 参数解析灰度染色标签并转译为标头透传")
    void testExtractTagFromQueryParam() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(true);
        GatewayTrafficTagFilter filter = new GatewayTrafficTagFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?traffic_tag=canary-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            assertEquals("canary-1", ex.getAttribute(GatewayConstants.ATTR_TRAFFIC_TAG));
            assertEquals("canary-1", ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TRAFFIC_TAG));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("测试当 Header 与 Query 同时存在时，Header 优先级高于 Query")
    void testHeaderPrecedenceOverQuery() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(true);
        GatewayTrafficTagFilter filter = new GatewayTrafficTagFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?traffic_tag=query-tag")
                .header(GatewayConstants.X_TRAFFIC_TAG, "header-tag")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            assertEquals("header-tag", ex.getAttribute(GatewayConstants.ATTR_TRAFFIC_TAG));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("测试自定义 TrafficTagResolver SPI 注入：根据已认证租户属性执行动态打标")
    void testCustomTrafficTagResolverBasedOnTenant() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(true);

        // 自定义 SPI 实现：若已认证租户为 vip-tenant，则染色为 vip-canary
        TrafficTagResolver customResolver = exchange -> {
            String tenantId = exchange.getAttribute(GatewayConstants.ATTR_TENANT_ID);
            return "vip-tenant".equals(tenantId) ? "vip-canary" : null;
        };
        GatewayTrafficTagFilter filter = new GatewayTrafficTagFilter(props, customResolver);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, "vip-tenant");

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            assertEquals("vip-canary", ex.getAttribute(GatewayConstants.ATTR_TRAFFIC_TAG));
            assertEquals("vip-canary", ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TRAFFIC_TAG));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("测试决议器返回空时强制剥离入站伪造染色标头，防止伪造链路穿透")
    void testForgedTrafficTagSanitizedWhenResolverReturnsNull() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(true);

        // 自定义决议器拒绝对外部请求打标 (返回 null)
        TrafficTagResolver denyAllResolver = exchange -> null;
        GatewayTrafficTagFilter filter = new GatewayTrafficTagFilter(props, denyAllResolver);

        // 外部恶意请求试图私自注入 X-Traffic-Tag: malicious-canary
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_TRAFFIC_TAG, "malicious-canary")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            assertNull(ex.getAttribute(GatewayConstants.ATTR_TRAFFIC_TAG));
            assertNull(ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TRAFFIC_TAG), "伪造染色标头必须被物理剥离");
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("测试自定义决议器强制覆盖并纠正外部传入的染色标头")
    void testCustomResolverOverridesExternalHeader() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(true);

        // 权威决议器强制决议为 official-lane
        TrafficTagResolver officialResolver = exchange -> "official-lane";
        GatewayTrafficTagFilter filter = new GatewayTrafficTagFilter(props, officialResolver);

        // 外部请求传入了不同的标头
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_TRAFFIC_TAG, "unauthorized-lane")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            assertEquals("official-lane", ex.getAttribute(GatewayConstants.ATTR_TRAFFIC_TAG));
            assertEquals("official-lane", ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TRAFFIC_TAG));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("测试无染色标签时正常放行且不污染上下文")
    void testNoTagPassThrough() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(true);
        GatewayTrafficTagFilter filter = new GatewayTrafficTagFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            assertNull(ex.getAttribute(GatewayConstants.ATTR_TRAFFIC_TAG));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("测试配置关闭时直接跳过解析")
    void testDisabledBypass() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(false);
        GatewayTrafficTagFilter filter = new GatewayTrafficTagFilter(props);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_TRAFFIC_TAG, "gray")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            executed.set(true);
            assertNull(ex.getAttribute(GatewayConstants.ATTR_TRAFFIC_TAG));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }
}
