package io.github.atengk.cloud.sample.gateway;

import cn.dev33.satoken.same.SaSameUtil;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 网关集中安全鉴权、零信任同源防御与响应式全链路追踪端到端对抗性集成测试 (100% 离线自洽)
 *
 * <p>全面覆盖以下生产安全核心语义：
 * <ul>
 *     <li>白名单免密直接放行与 X-Trace-Id 响应头回写；</li>
 *     <li>受保护接口未登录无 Token 访问时的 HTTP 401 统一契约转译；</li>
 *     <li>携带伪造/无效 Token 访问时的 HTTP 401 阻断拦截；</li>
 *     <li>合法登录会话携带 Token 访问受保护接口正常放行；</li>
 *     <li>鉴权通过后下游微服务权威重注 X-User-Id 与 X-Tenant-Id 强断言；</li>
 *     <li>登录态租户 ID 强制覆盖外部伪造租户标头 (Tenant Invariant 防串防越权)；</li>
 *     <li>网关动态签发内部同源 Same-Token 并成功通过 SaSameUtil.checkToken 验签；</li>
 *     <li>响应式 ContextView 链路追踪标头 X-Trace-Id 跨过滤器透明透传。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public class SampleGatewayAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("测试白名单接口免密放行且出站自动回写 X-Trace-Id")
    void testWhitelistEndpointAllowedWithTraceId() {
        webTestClient.get()
                .uri("/api/mock/hello")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo("gateway-ok");
    }

    @Test
    @DisplayName("测试未登录访问受保护接口拦截并返回规范 HTTP 401 Result")
    void testProtectedEndpointWithoutTokenBlockedWith401() {
        webTestClient.get()
                .uri("/api/mock/protected")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(401)
                .jsonPath("$.msg").value(containsString("未登录"))
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试携带伪造无效 Token 访问受保护接口拦截并返回 HTTP 401")
    void testProtectedEndpointWithInvalidTokenBlockedWith401() {
        webTestClient.get()
                .uri("/api/mock/protected")
                .header("satoken", "invalid-forged-token-abc-xyz")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(401)
                .jsonPath("$.msg").value(containsString("未登录"))
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试登录获取 Token 后正常访问受保护接口并校验 Same-Token 验签")
    @SuppressWarnings("unchecked")
    void testAuthenticatedAccessWithUserAndSameTokenInjection() {
        // 1. 调用 Mock 登录接口获取合法 Token
        EntityExchangeResult<Map> loginResult = webTestClient.get()
                .uri("/api/auth/login?userId=20001&tenantId=tenant-test-999")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<String, Object> body = loginResult.getResponseBody();
        assertNotNull(body);
        Map<String, String> data = (Map<String, String>) body.get("data");
        assertNotNull(data);
        String tokenName = data.get("tokenName");
        String tokenValue = data.get("tokenValue");

        // 2. 携带合法 Token 访问受保护接口
        webTestClient.get()
                .uri("/api/mock/protected")
                .header(tokenName, tokenValue)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo("protected-access-ok");

        // 3. 携带 Token 并故意伪造外部 X-Tenant-Id，强断言下游收到的是 Token 内部真实租户与注入的 Same-Token
        EntityExchangeResult<Map> echoResult = webTestClient.get()
                .uri("/api/mock/echo-headers")
                .header(tokenName, tokenValue)
                .header(GatewayConstants.X_TENANT_ID, "forged-external-tenant-666")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<String, Object> echoBody = echoResult.getResponseBody();
        assertNotNull(echoBody);
        Map<String, String> receivedHeaders = (Map<String, String>) echoBody.get("data");
        assertNotNull(receivedHeaders);

        // 强断言 1: 下游接收到网关权威重注的用户 ID (20001)
        org.junit.jupiter.api.Assertions.assertEquals("20001", receivedHeaders.get("x-user-id"));

        // 强断言 2: 下游接收到的租户 ID 为 Token 绑定的真实租户 (tenant-test-999)，伪造的 666 被强行覆盖
        org.junit.jupiter.api.Assertions.assertEquals("tenant-test-999", receivedHeaders.get("x-tenant-id"));

        // 强断言 3: 下游接收到由网关签发的内部同源 Same-Token，且通过 SaSameUtil 严格验签
        String receivedSameToken = receivedHeaders.get("same-token");
        assertNotNull(receivedSameToken, "下游必须接收到由网关签发的 Same-Token 内部受信凭据");
        assertDoesNotThrow(() -> SaSameUtil.checkToken(receivedSameToken), "Same-Token 必须通过数字签名有效性验证");
    }
}
