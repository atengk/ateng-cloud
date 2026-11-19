package io.github.atengk.cloud.sample.gateway;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.same.SaSameUtil;
import cn.dev33.satoken.stp.StpLogic;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 网关平台超级管理员通过 X-Tenant-Id 受控切换目标租户 WebFlux 端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-19
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ateng.cloud.gateway.tenant.allow-header=false",
                "ateng.cloud.gateway.tenant.allow-super-admin-switch=true",
                "ateng.cloud.gateway.tenant.platform-tenant-ids[0]=000000",
                "ateng.cloud.gateway.tenant.super-admin-roles[0]=super_admin"
        }
)
@AutoConfigureWebTestClient
class SampleGatewayTenantSwitchIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SampleGatewayTenantSwitchIntegrationTest.class);

    @Autowired
    private WebTestClient webTestClient;

    @AfterEach
    void tearDown() {
        try {
            logoutIfRegistered("admin", "000000_1001");
            logoutIfRegistered("admin", "000000_1002");
            logoutIfRegistered("admin", "tenant-other_1003");
        } catch (Exception e) {
            log.debug("测试会话清理跳过: {}", e.getMessage());
        }
        SaReactorSyncHolder.clearContext();
    }

    private void logoutIfRegistered(String loginType, String loginId) {
        StpLogic logic = SaManager.stpLogicMap != null ? SaManager.stpLogicMap.get(loginType) : null;
        if (logic != null) {
            logic.logout(loginId);
        }
    }

    @Test
    @DisplayName("验证匿名剥离、普通用户锁定、非平台超管拒绝及平台超管受控切换目标租户与 SA-SAME-TOKEN 透传")
    @SuppressWarnings("unchecked")
    void testSuperAdminTenantSwitchAndZeroTrustIsolation() {
        // 1. 匿名白名单请求即使携带 X-Tenant-Id，因 allow-header=false 仍被物理剥离
        webTestClient.get()
                .uri("/api/admin/public/notice")
                .header(GatewayConstants.X_TENANT_ID, "tenant-target-999")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.tenantId").isEmpty();

        // 2. 普通运营用户（platform tenant=000000，roles=operator）携带 X-Tenant-Id 仍强制锁定为 000000
        Map<String, Object> normalAdmin = loginAdmin("1002", "000000", "operator");
        webTestClient.get()
                .uri("/api/admin/dashboard")
                .header((String) normalAdmin.get("tokenName"), (String) normalAdmin.get("tokenValue"))
                .header(GatewayConstants.X_TENANT_ID, "tenant-target-999")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.userId").isEqualTo("1002")
                .jsonPath("$.data.tenantId").isEqualTo("000000");

        // 3. 非平台租户超管（tenant=tenant-other，roles=super_admin）无法跨租户切换
        Map<String, Object> nonPlatformSuper = loginAdmin("1003", "tenant-other", "super_admin");
        webTestClient.get()
                .uri("/api/admin/dashboard")
                .header((String) nonPlatformSuper.get("tokenName"), (String) nonPlatformSuper.get("tokenValue"))
                .header(GatewayConstants.X_TENANT_ID, "tenant-target-999")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.userId").isEqualTo("1003")
                .jsonPath("$.data.tenantId").isEqualTo("tenant-other");

        // 4. 平台租户 + super_admin 携带合法目标 X-Tenant-Id 成功切换，并携带 SA-SAME-TOKEN
        Map<String, Object> platformSuper = loginAdmin("1001", "000000", "super_admin");
        EntityExchangeResult<Map> switchedResult = webTestClient.get()
                .uri("/api/admin/dashboard")
                .header((String) platformSuper.get("tokenName"), (String) platformSuper.get("tokenValue"))
                .header(GatewayConstants.X_TENANT_ID, "tenant-target-999")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<String, Object> data = (Map<String, Object>) switchedResult.getResponseBody().get("data");
        assertNotNull(data);
        assertEquals("1001", data.get("userId"));
        assertEquals("tenant-target-999", data.get("tenantId"));
        Map<String, String> rawHeaders = (Map<String, String>) data.get("rawHeaders");
        assertNotNull(rawHeaders);
        assertDoesNotThrow(() -> SaSameUtil.checkToken(rawHeaders.get("sa-same-token")));

        // 5. 平台超管携带非法格式 X-Tenant-Id（路径穿越串）触发 400 Fail-Fast
        webTestClient.get()
                .uri("/api/admin/dashboard")
                .header((String) platformSuper.get("tokenName"), (String) platformSuper.get("tokenValue"))
                .header(GatewayConstants.X_TENANT_ID, "../evil-tenant")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(400);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loginAdmin(String userId, String tenantId, String roles) {
        EntityExchangeResult<Map> result = webTestClient.get()
                .uri("/api/admin/login?userId={uid}&tenantId={tid}&roles={roles}", userId, tenantId, roles)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();
        Map<String, Object> body = result.getResponseBody();
        assertNotNull(body);
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertNotNull(data);
        return data;
    }
}
