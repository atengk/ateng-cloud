package io.github.atengk.cloud.sample.gateway;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 网关 Same-Token 零信任同源开关关闭场景端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ateng.cloud.gateway.security.same-token-enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false"
        }
)
@AutoConfigureWebTestClient
@DisplayName("Same-Token 开关关闭场景端到端测试")
class SampleGatewaySameTokenDisabledIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SampleGatewaySameTokenDisabledIntegrationTest.class);

    @Autowired
    private WebTestClient webTestClient;

    @AfterEach
    void tearDown() {
        try {
            StpUtil.logout("30001");
        } catch (Exception e) {
            log.debug("清理测试登录态跳过: {}", e.getMessage());
        }
        SaReactorSyncHolder.clearContext();
    }

    @Test
    @DisplayName("关闭 same-token-enabled 时白名单与已登录请求均不再向下游注入 Same-Token 标头")
    @SuppressWarnings("unchecked")
    void testSameTokenNotInjectedWhenDisabled() {
        // 1. 白名单请求不应注入 Same-Token 与 SAME_TOKEN
        EntityExchangeResult<Map> whitelistResult = webTestClient.get()
                .uri("/api/auth/inspect-context")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<String, Object> whitelistData = (Map<String, Object>) whitelistResult.getResponseBody().get("data");
        assertNotNull(whitelistData);
        assertNull(whitelistData.get("sameToken"), "关闭 same-token-enabled 时不应向下游注入 Same-Token");
        Map<String, String> whitelistHeaders = (Map<String, String>) whitelistData.get("rawHeaders");
        assertNotNull(whitelistHeaders);
        assertFalse(whitelistHeaders.containsKey("same-token"));
        assertFalse(whitelistHeaders.containsKey("same_token"));

        // 2. 登录获取 Token 后，仍正常注入用户与租户身份，但不注入 Same-Token
        EntityExchangeResult<Map> loginResult = webTestClient.get()
                .uri("/api/auth/login?userId=30001&tenantId=tenant-disabled-check")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<String, Object> loginData = (Map<String, Object>) loginResult.getResponseBody().get("data");
        String tokenName = (String) loginData.get("tokenName");
        String tokenValue = (String) loginData.get("tokenValue");

        EntityExchangeResult<Map> authResult = webTestClient.get()
                .uri("/api/auth/inspect-context")
                .header(tokenName, tokenValue)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<String, Object> authData = (Map<String, Object>) authResult.getResponseBody().get("data");
        assertNotNull(authData);
        assertEquals("30001", authData.get("userId"));
        assertEquals("tenant-disabled-check", authData.get("tenantId"));
        assertNull(authData.get("sameToken"), "关闭 same-token-enabled 时已登录请求也不应注入 Same-Token");
        Map<String, String> authHeaders = (Map<String, String>) authData.get("rawHeaders");
        assertNotNull(authHeaders);
        assertFalse(authHeaders.containsKey("same-token"));
        assertFalse(authHeaders.containsKey("same_token"));
    }
}
