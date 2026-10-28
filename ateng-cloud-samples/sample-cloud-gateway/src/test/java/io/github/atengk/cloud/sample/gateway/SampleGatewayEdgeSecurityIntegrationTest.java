package io.github.atengk.cloud.sample.gateway;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.hamcrest.Matchers.containsString;

/**
 * 网关边缘防御与安全清洗端到端对抗性集成测试 (100% 离线自洽)
 *
 * <p>全面覆盖以下生产边缘防护能力：
 * <ul>
 *     <li>目录穿越（..、%2e%2e）与空字节（\0）探测的 HTTP 400 快速熔断；</li>
 *     <li>连续多斜杠（/// -> /）自动规范化折叠；</li>
 *     <li>公网入站敏感受信标头（X-User-Id、Same-Token 等）物理剥离与防伪造；</li>
 *     <li>可信代理真实 IP 倒推解析与下游 X-Real-IP 规范重注入；</li>
 *     <li>单 IP 与 CIDR 掩码黑名单的 HTTP 403 快速阻断。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public class SampleGatewayEdgeSecurityIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("测试目录穿越 (/api/../../admin) 快速熔断为 HTTP 400 Bad Request")
    void testPathTraversalDotDotBlocked() {
        webTestClient.get()
                .uri("/api/../../admin")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(400)
                .jsonPath("$.msg").value(containsString("目录穿越"))
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试十六进制编码目录穿越 (/api/%2e%2e/admin) 快速熔断为 HTTP 400")
    void testEncodedPathTraversalBlocked() {
        webTestClient.get()
                .uri("/api/%2e%2e/admin")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(400)
                .jsonPath("$.msg").value(containsString("目录穿越"))
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试包含空字节畸形请求快速熔断为 HTTP 400")
    void testNullByteBlocked() {
        webTestClient.get()
                .uri("/api/mock/hello%00evil")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(400)
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试多斜杠规范化折叠 (///api///mock/hello -> /mock/hello) 正常放行")
    void testMultiSlashNormalization() {
        webTestClient.get()
                .uri("///api///mock/hello")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo("gateway-ok");
    }

    @Test
    @DisplayName("测试入站敏感标头物理剥离清洗 (X-User-Id, Same-Token 等)")
    void testHeaderSanitization() {
        webTestClient.get()
                .uri("/api/mock/echo-headers")
                .header(GatewayConstants.X_USER_ID, "forged-user-999")
                .header(GatewayConstants.X_TENANT_ID, "forged-tenant-888")
                .header(GatewayConstants.X_IGNORE_TENANT, "true")
                .header(GatewayConstants.SAME_TOKEN, "forged-same-token-signature")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                // 强断言：下游接收到的标头字典中绝对不得包含伪造的敏感标头
                .jsonPath("$.data['x-user-id']").doesNotExist()
                .jsonPath("$.data['x-tenant-id']").doesNotExist()
                .jsonPath("$.data['x-ignore-tenant']").doesNotExist()
                .jsonPath("$.data['same-token']").doesNotExist();
    }

    @Test
    @DisplayName("测试基于可信反向代理跳数倒推解析真实 IP 并注入 X-Real-IP")
    void testClientRealIpResolution() {
        // 模拟外部请求链: 伪造 IP(1.1.1.1) -> 真实公网客户端(203.0.113.195) -> 前置 SLB(自动追加真实 IP)
        webTestClient.get()
                .uri("/api/mock/echo-headers")
                .header(GatewayConstants.X_FORWARDED_FOR, "1.1.1.1, 203.0.113.195")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                // 强断言：下游接收到的 X-Real-IP 必须为跳过 1 层前置代理后的真实公网 IP (203.0.113.195)
                .jsonPath("$.data['x-real-ip']").isEqualTo("203.0.113.195");
    }

    @Test
    @DisplayName("测试单 IP 黑名单快速熔断为 HTTP 403 Forbidden")
    void testExactIpBlacklistBlocked() {
        webTestClient.get()
                .uri("/api/mock/hello")
                .header(GatewayConstants.X_FORWARDED_FOR, "192.168.100.1")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.msg").value(containsString("黑名单"))
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试 CIDR 掩码黑名单 (10.99.0.0/16) 快速熔断为 HTTP 403")
    void testCidrIpBlacklistBlocked() {
        webTestClient.get()
                .uri("/api/mock/hello")
                .header(GatewayConstants.X_FORWARDED_FOR, "10.99.12.34")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.msg").value(containsString("黑名单"))
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试合法未被拉黑 IP 正常放行通过")
    void testNormalIpAllowed() {
        webTestClient.get()
                .uri("/api/mock/hello")
                .header(GatewayConstants.X_FORWARDED_FOR, "203.0.113.50")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo("gateway-ok");
    }
}
