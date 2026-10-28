package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
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
