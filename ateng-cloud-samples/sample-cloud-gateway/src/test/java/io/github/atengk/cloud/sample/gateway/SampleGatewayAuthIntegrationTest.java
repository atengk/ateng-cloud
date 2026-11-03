package io.github.atengk.cloud.sample.gateway;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.same.SaSameUtil;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *     <li>退出登录会话注销后 Token 即刻失效防越权。</li>
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

    @AfterEach
    void tearDown() {
        try {
            StpUtil.logout("20001");
            StpUtil.logout("10001");
        } catch (Exception ignored) {
        }
        SaReactorSyncHolder.clearContext();
    }

    @Test
    @DisplayName("测试白名单接口免密放行且出站自动回写 X-Trace-Id")
    void testWhitelistEndpointAllowedWithTraceId() {
        webTestClient.get()
                .uri("/api/auth/inspect-context")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.traceId").isNotEmpty()
                .jsonPath("$.data.sameToken").isNotEmpty();
    }

    @Test
    @DisplayName("测试未登录访问受保护接口拦截并返回规范 HTTP 401 Result")
    void testProtectedEndpointWithoutTokenBlockedWith401() {
        webTestClient.get()
                .uri("/api/auth/user-info")
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
                .uri("/api/auth/user-info")
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
        // 1. 调用登录接口获取合法 Token
        EntityExchangeResult<Map> loginResult = webTestClient.get()
                .uri("/api/auth/login?userId=20001&tenantId=tenant-test-999")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<String, Object> body = loginResult.getResponseBody();
        assertNotNull(body);
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertNotNull(data);
        String tokenName = (String) data.get("tokenName");
        String tokenValue = (String) data.get("tokenValue");
        assertEquals("20001", data.get("loginId"));
        assertEquals("tenant-test-999", data.get("tenantId"));

        // 2. 携带合法 Token 访问受保护用户信息接口
        webTestClient.get()
                .uri("/api/auth/user-info")
                .header(tokenName, tokenValue)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.userId").isEqualTo("20001")
                .jsonPath("$.data.tenantId").isEqualTo("tenant-test-999")
                .jsonPath("$.data.status").isEqualTo("ACTIVE");

        // 3. 携带 Token 并故意伪造外部 X-Tenant-Id，强断言下游收到的是 Token 内部真实租户与注入的 Same-Token
        EntityExchangeResult<Map> echoResult = webTestClient.get()
                .uri("/api/auth/inspect-context")
                .header(tokenName, tokenValue)
                .header(GatewayConstants.X_TENANT_ID, "forged-external-tenant-666")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<String, Object> echoBody = echoResult.getResponseBody();
        assertNotNull(echoBody);
        Map<String, Object> echoData = (Map<String, Object>) echoBody.get("data");
        assertNotNull(echoData);

        // 强断言 1: 下游接收到网关权威重注的用户 ID (20001)
        assertEquals("20001", echoData.get("userId"));

        // 强断言 2: 下游接收到的租户 ID 为 Token 绑定的真实租户 (tenant-test-999)，伪造的 666 被强行覆盖
        assertEquals("tenant-test-999", echoData.get("tenantId"));

        // 强断言 3: 下游接收到由网关签发的内部同源 Same-Token，且通过 SaSameUtil 严格验签
        String receivedSameToken = (String) echoData.get("sameToken");
        assertNotNull(receivedSameToken, "下游必须接收到由网关签发的 Same-Token 内部受信凭据");
        assertDoesNotThrow(() -> SaSameUtil.checkToken(receivedSameToken), "Same-Token 必须通过数字签名有效性验证");

        // 强断言 4: 强类型 rawHeaders 中校验权威标头
        Map<String, String> rawHeaders = (Map<String, String>) echoData.get("rawHeaders");
        assertNotNull(rawHeaders);
        assertEquals("20001", rawHeaders.get("x-user-id"));
        assertEquals("tenant-test-999", rawHeaders.get("x-tenant-id"));
    }

    @Test
    @DisplayName("测试退出登录后 Token 立即失效")
    @SuppressWarnings("unchecked")
    void testLogoutSessionRevocation() {
        // 1. 登录
        EntityExchangeResult<Map> loginResult = webTestClient.get()
                .uri("/api/auth/login?userId=10001&tenantId=tenant-111")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<String, Object> body = loginResult.getResponseBody();
        assertNotNull(body);
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        String tokenName = (String) data.get("tokenName");
        String tokenValue = (String) data.get("tokenValue");

        // 2. 正常访问受保护接口
        webTestClient.get()
                .uri("/api/auth/user-info")
                .header(tokenName, tokenValue)
                .exchange()
                .expectStatus().isOk();

        // 3. 退出登录
        webTestClient.post()
                .uri("/api/auth/logout")
                .header(tokenName, tokenValue)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200);

        // 4. 再次访问受保护接口即刻返回 401
        webTestClient.get()
                .uri("/api/auth/user-info")
                .header(tokenName, tokenValue)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
