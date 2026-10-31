package io.github.atengk.cloud.sentinel.handler;

import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AtengBlockExceptionHandler} 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengBlockExceptionHandlerTest {

    private ObjectMapper objectMapper;
    private AtengSentinelProperties properties;
    private AtengBlockExceptionHandler handler;

    @BeforeEach
    void setUp() {
        this.objectMapper = new ObjectMapper();
        this.properties = new AtengSentinelProperties();
        this.handler = new AtengBlockExceptionHandler(objectMapper, properties);
    }

    @Test
    @DisplayName("流控阻断 (FlowException)：对齐 HTTP 429 且返回 FLOW_LIMIT (4201)")
    void testHandleFlowException() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader("X-Forwarded-For", "203.0.113.195, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, "order-resource", new FlowException("test flow block"));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).contains("application/json");

        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getCode());
        assertThat(json.get("msg").asText()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getMsg());
    }

    @Test
    @DisplayName("降级熔断 (DegradeException)：对齐 HTTP 503 且返回 DEGRADE (4202)")
    void testHandleDegradeException() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/pay");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, "pay-resource", new DegradeException("test degrade block"));

        assertThat(response.getStatus()).isEqualTo(503);
        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(SentinelResultCode.DEGRADE.getCode());
        assertThat(json.get("msg").asText()).isEqualTo(SentinelResultCode.DEGRADE.getMsg());
    }

    @Test
    @DisplayName("热点参数限流 (ParamFlowException)：对齐 HTTP 429 且返回 PARAM_FLOW_LIMIT (4203)")
    void testHandleParamFlowException() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/goods/101");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, "goods-resource", new ParamFlowException("goods-resource", "101"));

        assertThat(response.getStatus()).isEqualTo(429);
        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(SentinelResultCode.PARAM_FLOW_LIMIT.getCode());
    }

    @Test
    @DisplayName("系统自适应保护 (SystemBlockException)：对齐 HTTP 429 且返回 SYSTEM_BLOCK (4204)")
    void testHandleSystemBlockException() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/heavy-calc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, "calc-resource", new SystemBlockException("heavy-calc", "cpu"));

        assertThat(response.getStatus()).isEqualTo(429);
        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(SentinelResultCode.SYSTEM_BLOCK.getCode());
    }

    @Test
    @DisplayName("授权黑白名单拦截 (AuthorityException)：对齐 HTTP 429 且返回 AUTHORITY_BLOCK (4205)")
    void testHandleAuthorityException() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, "admin-resource", new AuthorityException("admin-resource", "app-a"));

        assertThat(response.getStatus()).isEqualTo(429);
        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(SentinelResultCode.AUTHORITY_BLOCK.getCode());
    }

    @Test
    @DisplayName("关闭物理状态码对齐：HTTP 物理状态返回 200，Result.code 保持 4201")
    void testHttpStatusAlignDisabled() throws Exception {
        properties.getBlockHandler().setHttpStatusAlign(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, "order-resource", new FlowException("test flow block"));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getCode());
    }

    @Test
    @DisplayName("已提交响应防卫：当 response.isCommitted() 为 true 时安全跳过写入，不抛次生异常")
    void testHandleResponseAlreadyCommitted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        handler.handle(request, response, "order-resource", new FlowException("test flow block"));

        // 验证未覆盖状态码与写入内容
        assertThat(response.getContentAsString()).isEmpty();
    }
}
