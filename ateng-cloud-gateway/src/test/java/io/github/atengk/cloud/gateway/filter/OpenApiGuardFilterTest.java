package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.properties.AtengGatewayOpenApiProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayOpenApiProperties.GuardMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微服务 OpenAPI 接口文档安全守卫过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class OpenApiGuardFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("测试非文档路径直接无感放行")
    void testNonOpenApiPathPassThrough() {
        AtengGatewayOpenApiProperties props = new AtengGatewayOpenApiProperties();
        props.setEnabled(true);
        props.setGuardMode(GuardMode.DISABLED);

        OpenApiGuardFilter filter = new OpenApiGuardFilter(props, objectMapper);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/user/info").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            executed.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("测试 DISABLED 模式下文档路径直接拦截返回 HTTP 404")
    void testDisabledModeBlocksWith404() {
        AtengGatewayOpenApiProperties props = new AtengGatewayOpenApiProperties();
        props.setEnabled(true);
        props.setGuardMode(GuardMode.DISABLED);

        OpenApiGuardFilter filter = new OpenApiGuardFilter(props, objectMapper);

        MockServerHttpRequest request = MockServerHttpRequest.get("/v3/api-docs/swagger-config").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            executed.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
        assertTrue(!executed.get(), "DISABLED 模式应熔断下游");
        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("测试 BASIC 模式下凭据缺失返回 HTTP 401 并附带 WWW-Authenticate 标头")
    void testBasicModeMissingAuthReturns401() {
        AtengGatewayOpenApiProperties props = new AtengGatewayOpenApiProperties();
        props.setEnabled(true);
        props.setGuardMode(GuardMode.BASIC);
        props.getBasicAuth().setUsername("admin");
        props.getBasicAuth().setPassword("secret123");

        OpenApiGuardFilter filter = new OpenApiGuardFilter(props, objectMapper);

        MockServerHttpRequest request = MockServerHttpRequest.get("/docs").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertNotNull(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    @DisplayName("测试 BASIC 模式下正确凭据放行文档资源通过")
    void testBasicModeValidAuthPasses() {
        AtengGatewayOpenApiProperties props = new AtengGatewayOpenApiProperties();
        props.setEnabled(true);
        props.setGuardMode(GuardMode.BASIC);
        props.getBasicAuth().setUsername("admin");
        props.getBasicAuth().setPassword("secret123");

        OpenApiGuardFilter filter = new OpenApiGuardFilter(props, objectMapper);

        String credentials = Base64.getEncoder().encodeToString("admin:secret123".getBytes(StandardCharsets.UTF_8));
        MockServerHttpRequest request = MockServerHttpRequest.get("/v3/api-docs/swagger-config")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            executed.set(true);
            return Mono.empty();
        }).block();

        assertTrue(executed.get(), "认证通过应放行文档资源");
    }

    @Test
    @DisplayName("测试 BASIC 模式下入口路径 /docs 认证通过后自动重定向至 Swagger UI")
    void testBasicModeRedirectsEntryPath() {
        AtengGatewayOpenApiProperties props = new AtengGatewayOpenApiProperties();
        props.setEnabled(true);
        props.setGuardMode(GuardMode.BASIC);
        props.getBasicAuth().setUsername("admin");
        props.getBasicAuth().setPassword("secret123");

        OpenApiGuardFilter filter = new OpenApiGuardFilter(props, objectMapper);

        String credentials = Base64.getEncoder().encodeToString("admin:secret123".getBytes(StandardCharsets.UTF_8));
        MockServerHttpRequest request = MockServerHttpRequest.get("/docs")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
        assertEquals("/swagger-ui.html", exchange.getResponse().getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    @DisplayName("测试 PUBLIC 模式下入口路径 /docs 自动重定向至 Swagger UI")
    void testPublicModeRedirectsEntryPath() {
        AtengGatewayOpenApiProperties props = new AtengGatewayOpenApiProperties();
        props.setEnabled(true);
        props.setGuardMode(GuardMode.PUBLIC);

        OpenApiGuardFilter filter = new OpenApiGuardFilter(props, objectMapper);

        MockServerHttpRequest request = MockServerHttpRequest.get("/docs").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
        assertEquals("/swagger-ui.html", exchange.getResponse().getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    @DisplayName("测试 PUBLIC 模式下文档子路径直接放行访问")
    void testPublicModePassesSubpath() {
        AtengGatewayOpenApiProperties props = new AtengGatewayOpenApiProperties();
        props.setEnabled(true);
        props.setGuardMode(GuardMode.PUBLIC);

        OpenApiGuardFilter filter = new OpenApiGuardFilter(props, objectMapper);

        MockServerHttpRequest request = MockServerHttpRequest.get("/v3/api-docs/swagger-config").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            executed.set(true);
            return Mono.empty();
        }).block();

        assertTrue(executed.get(), "PUBLIC 模式下子路径应直接放行");
    }
}
