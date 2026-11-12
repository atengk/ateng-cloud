package io.github.atengk.cloud.sample.gateway;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.hamcrest.Matchers.containsString;

/**
 * 网关有界 Nonce 防重放容量上限 (nonce-max-capacity) 端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ateng.cloud.gateway.sign.nonce-max-capacity=2",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false"
        }
)
@AutoConfigureWebTestClient(timeout = "15000")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("有界 Nonce 防重放容量上限端到端测试")
class SampleGatewayBoundedNonceIntegrationTest {

    private static final String SAMPLE_APP_ID = "sample-app";
    private static final String SAMPLE_APP_SECRET = "sample-secret-123456";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("配置 nonce-max-capacity=2 时前 2 次正常放行，第 3 次新 Nonce 满载触发 Fail-Closed 401 拦截")
    void testNonceMaxCapacityFailClosedEndToEnd() throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());

        for (int i = 1; i <= 3; i++) {
            String nonce = "e2e-bounded-nonce-" + i;
            String canonical = "appId=" + SAMPLE_APP_ID
                    + "&nonce=" + nonce
                    + "&timestamp=" + timestamp
                    + "&orderId=ORD-BOUNDED-" + i;
            String signature = calculateHmacSha256(canonical, SAMPLE_APP_SECRET);

            WebTestClient.ResponseSpec response = webTestClient.get()
                    .uri("/api/open/merchant/query-order?orderId=ORD-BOUNDED-" + i)
                    .header(GatewayConstants.X_APP_ID, SAMPLE_APP_ID)
                    .header(GatewayConstants.X_TIMESTAMP, timestamp)
                    .header(GatewayConstants.X_NONCE, nonce)
                    .header(GatewayConstants.X_SIGNATURE, signature)
                    .exchange();

            if (i <= 2) {
                response.expectStatus().isOk()
                        .expectBody()
                        .jsonPath("$.code").isEqualTo(200)
                        .jsonPath("$.data.orderId").isEqualTo("ORD-BOUNDED-" + i);
            } else {
                response.expectStatus().isUnauthorized()
                        .expectBody()
                        .jsonPath("$.code").isEqualTo(401)
                        .jsonPath("$.msg").value(containsString("重放"));
            }
        }
    }

    private String calculateHmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
