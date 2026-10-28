package io.github.atengk.cloud.sample.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * 网关全局统一异常与响应式路由端到端集成测试 (100% 离线自洽)
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public class SampleGatewayExceptionIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("测试未匹配路由 404 转译为规范 Result JSON 并绑定 traceId")
    void testNotFoundRoute() {
        webTestClient.get()
                .uri("/not-exists-endpoint")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .jsonPath("$.code").isEqualTo(404)
                .jsonPath("$.msg").value(containsString("未找到"))
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试正常响应与统一契约")
    void testNormalRoute() {
        webTestClient.get()
                .uri("/mock/hello")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo("gateway-ok");
    }

    @Test
    @DisplayName("测试业务受管 BusinessException 异常 100% 保真透传错误码与描述")
    void testBusinessException() {
        webTestClient.get()
                .uri("/mock/business-error")
                .exchange()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .jsonPath("$.code").isEqualTo(1001)
                .jsonPath("$.msg").isEqualTo("业务断言异常触发")
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试系统未知异常 500 强制脱敏且严禁向公网泄漏敏感堆栈")
    void testServerErrorMasking() {
        webTestClient.get()
                .uri("/mock/server-error")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .jsonPath("$.code").isEqualTo(500)
                .jsonPath("$.msg").isEqualTo("系统服务繁忙，请稍后重试")
                .jsonPath("$.msg").value(not(containsString("数据库异常")))
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试 Sa-Token 未登录异常统一转译为 HTTP 401 Result 契约")
    void testSaTokenNotLoginTranslation() {
        webTestClient.get()
                .uri("/mock/satoken-not-login")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .jsonPath("$.code").isEqualTo(401)
                .jsonPath("$.msg").value(containsString("未登录"))
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试 Sa-Token 无权限异常统一转译为 HTTP 403 Result 契约")
    void testSaTokenNotPermissionTranslation() {
        webTestClient.get()
                .uri("/mock/satoken-not-permission")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.msg").value(containsString("无权限"))
                .jsonPath("$.traceId").isNotEmpty();
    }
}
