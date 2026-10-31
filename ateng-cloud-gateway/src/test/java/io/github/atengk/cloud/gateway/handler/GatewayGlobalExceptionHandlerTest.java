package io.github.atengk.cloud.gateway.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import reactor.netty.http.client.PrematureCloseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网关全局统一响应式异常处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("网关全局统一响应式异常处理器单元测试")
class GatewayGlobalExceptionHandlerTest {

    private GatewayGlobalExceptionHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new GatewayGlobalExceptionHandler(objectMapper);
    }

    @Test
    @DisplayName("测试下游连接过早关闭 PrematureCloseException 转译为 HTTP 502 Bad Gateway")
    void testPrematureCloseException() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/order/detail").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        PrematureCloseException ex = PrematureCloseException.TEST_EXCEPTION;
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
        assertNotNull(exchange.getResponse().getHeaders().getFirst(GatewayConstants.X_TRACE_ID));

        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(502, json.get("code").asInt());
        assertTrue(json.get("msg").asText().contains("Bad Gateway"));
        assertEquals(exchange.getResponse().getHeaders().getFirst(GatewayConstants.X_TRACE_ID), json.get("traceId").asText());
    }

    @Test
    @DisplayName("测试信道关闭 ClosedChannelException 转译为 HTTP 502 Bad Gateway")
    void testClosedChannelException() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/user/info").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ClosedChannelException ex = new ClosedChannelException();
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());

        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(502, json.get("code").asInt());
        assertTrue(json.get("msg").asText().contains("Bad Gateway"));
    }

    @Test
    @DisplayName("测试 Sentinel FlowException 自适应转译为 HTTP 429 且附加 Retry-After 标头")
    void testSentinelFlowException() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/hot/item").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        FlowException ex = new FlowException("Flow limit exceeded");
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("1", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));

        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(429, json.get("code").asInt());
        assertTrue(json.get("msg").asText().contains("流量控制"));
    }

    @Test
    @DisplayName("测试 Sentinel ParamFlowException 自适应转译为 HTTP 429 且附加 Retry-After 标头")
    void testSentinelParamFlowException() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/goods/1001").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ParamFlowException ex = new ParamFlowException("Param flow limit");
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("1", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));

        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(429, json.get("code").asInt());
    }

    @Test
    @DisplayName("测试 Sentinel SystemBlockException 自适应转译为 HTTP 429 且附加 Retry-After 标头")
    void testSentinelSystemBlockException() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/heavy/calc").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        SystemBlockException ex = new SystemBlockException("System load high");
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("1", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));

        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(429, json.get("code").asInt());
    }

    @Test
    @DisplayName("测试 Sentinel DegradeException 自适应转译为 HTTP 503 熔断降级")
    void testSentinelDegradeException() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/pay/create").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        DegradeException ex = new DegradeException("Circuit breaker open");
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());

        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(503, json.get("code").asInt());
        assertTrue(json.get("msg").asText().contains("熔断降级"));
    }

    @Test
    @DisplayName("测试 Sentinel AuthorityException 自适应转译为 HTTP 403 授权拦截")
    void testSentinelAuthorityException() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/admin/config").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AuthorityException ex = new AuthorityException("App blacklisted");
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());

        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(403, json.get("code").asInt());
        assertTrue(json.get("msg").asText().contains("授权拦截"));
    }

    @Test
    @DisplayName("测试下游建连失败 ConnectException 转译为 HTTP 503")
    void testConnectException() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/user/profile").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ConnectException ex = new ConnectException("Connection refused");
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(503, json.get("code").asInt());
    }

    @Test
    @DisplayName("测试下游读取超时 TimeoutException 转译为 HTTP 504 Gateway Timeout")
    void testTimeoutException() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/report/export").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        TimeoutException ex = new TimeoutException("Response timeout after 10000ms");
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(504, json.get("code").asInt());
    }

    @Test
    @DisplayName("测试业务受管 BusinessException 100% 原始透传错误码与提示")
    void testBusinessException() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/biz/check").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        BusinessException ex = new BusinessException(20001, "账户余额不足，请充值");
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(20001, json.get("code").asInt());
        assertEquals("账户余额不足，请充值", json.get("msg").asText());
    }

    @Test
    @DisplayName("测试 Sa-Token NotLoginException 转译为 HTTP 401")
    void testSaTokenNotLoginException() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/secure/data").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        NotLoginException ex = new NotLoginException("token invalid", "login", NotLoginException.NOT_TOKEN);
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(401, json.get("code").asInt());
    }

    @Test
    @DisplayName("测试 Sa-Token NotPermissionException 转译为 HTTP 403")
    void testSaTokenNotPermissionException() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/secure/admin").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        NotPermissionException ex = new NotPermissionException("user:delete");
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(403, json.get("code").asInt());
    }

    @Test
    @DisplayName("测试 404 资源未找到 ResponseStatusException 转译为 HTTP 404")
    void testResponseStatusException404() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/non-existent").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "No static resource found");
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(404, json.get("code").asInt());
    }

    @Test
    @DisplayName("测试未知系统内部异常强制安全脱敏输出 HTTP 500")
    void testUnknownSystemExceptionMasking() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/crash").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        NullPointerException ex = new NullPointerException("Secret database connection password leaked here");
        handler.handle(exchange, ex).block();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(500, json.get("code").asInt());
        assertEquals("系统服务繁忙，请稍后重试", json.get("msg").asText());
        assertFalse(body.contains("Secret database connection password"));
    }
}
