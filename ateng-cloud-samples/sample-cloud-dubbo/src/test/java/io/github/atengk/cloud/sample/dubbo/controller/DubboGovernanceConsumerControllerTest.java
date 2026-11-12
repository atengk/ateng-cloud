package io.github.atengk.cloud.sample.dubbo.controller;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dubbo 微服务生产治理容灾 Web 控制器端到端集成测试
 * 覆盖：JSR-303 参数校验防卫、受管业务异常保真、底层异常因果转译、服务端 429 过载保护、客户端 Mock 降级与 Same-Token 校验
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public class DubboGovernanceConsumerControllerTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int liveDubboPort = TestSocketUtils.findAvailableTcpPort();
        int unreachablePort = TestSocketUtils.findAvailableTcpPort();
        registry.add("dubbo.protocol.port", () -> liveDubboPort);
        registry.add("test.governance.live.port", () -> liveDubboPort);
        registry.add("dubbo.test.reference.url", () -> "tri://127.0.0.1:" + liveDubboPort);
        registry.add("dubbo.consumer.direct-url", () -> "tri://127.0.0.1:" + liveDubboPort);
        registry.add("dubbo.test.unreachable.url", () -> "tri://127.0.0.1:" + unreachablePort);
        registry.add("ateng.cloud.dubbo.validation.enabled", () -> "true");
        registry.add("ateng.cloud.dubbo.overload.enabled", () -> "true");
        registry.add("ateng.cloud.dubbo.same-token.enabled", () -> "true");
        registry.add("ateng.cloud.dubbo.same-token.check", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        RpcContext.removeClientAttachment();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("验证 POST /validation 当入参合法时正常通过校验并成功返回创建用户数据")
    void testValidation_Success() throws Exception {
        String requestJson = """
                {
                    "username": "ateng-gov-admin",
                    "age": 28
                }
                """;

        mockMvc.perform(post("/sample/cloud/dubbo/governance/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.username", is("ateng-gov-admin")));
    }

    @Test
    @DisplayName("验证 POST /validation 当入参为空时被 Web 传输层 JSR-303 格式校验拦截并返回 400")
    void testValidation_WebFailFast() throws Exception {
        String invalidJson = """
                {
                    "username": "",
                    "age": 0
                }
                """;

        mockMvc.perform(post("/sample/cloud/dubbo/governance/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    @DisplayName("验证 POST /validation/rpc 绕过 Web 校验直接传入非法入参时，由 DubboValidationFilter 服务端拦截转译为 400")
    void testValidationRpcDirect_Failure() throws Exception {
        String invalidJson = """
                {
                    "username": "",
                    "age": 0
                }
                """;

        mockMvc.perform(post("/sample/cloud/dubbo/governance/validation/rpc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.msg", containsString("参数校验失败")));
    }

    @Test
    @DisplayName("验证 POST /validation/batch 传入合法批量用户集合时放行并成功创建全部用户")
    void testValidationBatch_Success() throws Exception {
        String validBatchJson = """
                [
                    {
                        "username": "batch-user-1",
                        "age": 22
                    },
                    {
                        "username": "batch-user-2",
                        "age": 30
                    }
                ]
                """;

        mockMvc.perform(post("/sample/cloud/dubbo/governance/validation/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBatchJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.length()", is(2)))
                .andExpect(jsonPath("$.data[0].username", is("batch-user-1")))
                .andExpect(jsonPath("$.data[1].username", is("batch-user-2")));
    }

    @Test
    @DisplayName("验证 POST /validation/batch 当集合内任一元素非法时，由 DubboValidationFilter 级联拦截转译为 400")
    void testValidationBatch_ElementFailure() throws Exception {
        String invalidBatchJson = """
                [
                    {
                        "username": "batch-user-valid",
                        "age": 25
                    },
                    {
                        "username": "",
                        "age": 0
                    }
                ]
                """;

        mockMvc.perform(post("/sample/cloud/dubbo/governance/validation/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBatchJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.msg", containsString("参数校验失败")))
                .andExpect(jsonPath("$.msg", containsString("[1]")));
    }

    @Test
    @DisplayName("验证 GET /business-exception 服务端抛出 BusinessException 时白名单放行并 100% 因果保真还原 404")
    void testBusinessException_CausalPreservation() throws Exception {
        mockMvc.perform(get("/sample/cloud/dubbo/governance/business-exception"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(404)))
                .andExpect(jsonPath("$.msg", containsString("用户记录不存在")));
    }

    @Test
    @DisplayName("验证 GET /custom-exception 服务端抛出底层私有异常时由 AtengDubboExceptionFilter 安全转译为 500")
    void testCustomException_Translation() throws Exception {
        mockMvc.perform(get("/sample/cloud/dubbo/governance/custom-exception"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(500)))
                .andExpect(jsonPath("$.msg", containsString("模拟底座数据库死锁超时")));
    }

    @Test
    @DisplayName("验证 GET /overload 服务端线程池耗尽时由 DubboOverloadFilter 拦截并转译为 429 限流保护")
    void testOverload_Protection() throws Exception {
        mockMvc.perform(get("/sample/cloud/dubbo/governance/overload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(429)))
                .andExpect(jsonPath("$.msg", containsString("线程池已耗尽")));
    }

    @Test
    @DisplayName("验证 GET /mock/fallback 当目标端口不可达时，消费端自动平滑触发 UserRpcServiceMock 降级返回兜底数据")
    void testMockFallback_UnreachableProvider() throws Exception {
        mockMvc.perform(get("/sample/cloud/dubbo/governance/mock/fallback")
                        .param("id", "888"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.id", is("888")))
                .andExpect(jsonPath("$.data.username", is("mock-user-fallback")))
                .andExpect(jsonPath("$.data.email", is("mock@example.com")));
    }

    @Test
    @DisplayName("验证 GET /mock/live 当服务健康时优先响应真实 Provider 数据")
    void testMockLive_HealthyProvider() throws Exception {
        mockMvc.perform(get("/sample/cloud/dubbo/governance/mock/live")
                        .param("id", "123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.id", is("123")))
                .andExpect(jsonPath("$.data.username", is("ateng_123")));
    }

    @Test
    @DisplayName("验证 GET /same-token 未显式传参时消费端自动注入有效 Same-Token 凭据并校验通过")
    void testSameToken_SuccessWhenAutoInjected() throws Exception {
        mockMvc.perform(get("/sample/cloud/dubbo/governance/same-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", containsString("Same-Token 受信凭据校验通过")));
    }

    @Test
    @DisplayName("验证 GET /same-token 传入篡改的无效凭据时服务端 Fail-Fast 401 阻断")
    void testSameToken_RejectionWhenInvalid() throws Exception {
        mockMvc.perform(get("/sample/cloud/dubbo/governance/same-token")
                        .header("Same-Token", "invalid-token-tampered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(401)))
                .andExpect(jsonPath("$.msg", containsString("Same-Token")));
    }

    @Test
    @DisplayName("验证 GET /same-token 传入空白凭据时服务端 Fail-Fast 401 阻断")
    void testSameToken_RejectionWhenBlank() throws Exception {
        mockMvc.perform(get("/sample/cloud/dubbo/governance/same-token")
                        .header("Same-Token", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(401)))
                .andExpect(jsonPath("$.msg", containsString("Same-Token")));
    }
}
