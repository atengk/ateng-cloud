package io.github.atengk.cloud.sample.gateway;

import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.RsaUtil;
import io.github.atengk.boot.core.util.Sm2Util;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关 STANDARD 统一规范签协议、RSA/SM2 多算法与出站响应加签端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
@DisplayName("统一规范签与多算法响应回签端到端集成测试")
class SampleGatewayStandardSignIntegrationTest {

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "ateng.cloud.gateway.sign.canonical-mode=STANDARD",
                    "ateng.cloud.gateway.sign.algorithm=RSA",
                    "ateng.cloud.gateway.sign.sign-response=true",
                    "spring.cloud.nacos.discovery.enabled=false",
                    "spring.cloud.nacos.config.enabled=false"
            }
    )
    @AutoConfigureWebTestClient(timeout = "15000")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @DisplayName("STANDARD + RSA 双向加验签端到端闭环")
    class RsaStandardSignTests {

        private static final KeyPair CLIENT_KP = RsaUtil.generateKeyPair(2048);
        private static final KeyPair SERVER_KP = RsaUtil.generateKeyPair(2048);

        @DynamicPropertySource
        static void registerRsaKeys(DynamicPropertyRegistry registry) {
            registry.add("ateng.cloud.gateway.sign.apps.rsa-merchant.rsa-public-key",
                    () -> RsaUtil.encodePublicKeyBase64(CLIENT_KP.getPublic()));
            registry.add("ateng.cloud.gateway.sign.rsa-private-key",
                    () -> RsaUtil.encodePrivateKeyBase64(SERVER_KP.getPrivate()));
        }

        @Autowired
        private WebTestClient webTestClient;

        @Test
        @DisplayName("POST /api/open/merchant/sync-order 验证 6 行规范串 RSA 验签并核验出站响应回签")
        void testStandardRsaSyncOrderAndVerifyResponseSignature() {
            String ts = String.valueOf(System.currentTimeMillis());
            String nonce = "e2e-rsa-" + System.nanoTime();
            String body = "{\"orderId\":\"ORD-RSA-001\",\"merchantId\":\"MCH-RSA\",\"amount\":88.50,\"channel\":\"WECHAT\"}";
            String canonical = "POST\n/api/open/merchant/sync-order\n" + ts + "\n" + nonce + "\n\n" + DigestUtil.sha256Hex(body);
            String sig = RsaUtil.signSha256Base64(canonical, RsaUtil.encodePrivateKeyBase64(CLIENT_KP.getPrivate()));

            EntityExchangeResult<byte[]> result = webTestClient.post()
                    .uri("/api/open/merchant/sync-order")
                    .header(GatewayConstants.X_APP_ID, "rsa-merchant")
                    .header(GatewayConstants.X_TIMESTAMP, ts)
                    .header(GatewayConstants.X_NONCE, nonce)
                    .header(GatewayConstants.X_SIGNATURE, sig)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().exists(GatewayConstants.X_SIGNATURE)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200)
                    .jsonPath("$.data.orderId").isEqualTo("ORD-RSA-001")
                    .returnResult();

            String respSig = result.getResponseHeaders().getFirst(GatewayConstants.X_SIGNATURE);
            String respTs = result.getResponseHeaders().getFirst(GatewayConstants.X_TIMESTAMP);
            String respNonce = result.getResponseHeaders().getFirst(GatewayConstants.X_NONCE);
            assertNotNull(respSig);
            assertEquals(ts, respTs);
            assertEquals(nonce, respNonce);

            String respCanonical = respTs + "\n" + respNonce + "\n" + DigestUtil.sha256Hex(result.getResponseBody());
            assertTrue(RsaUtil.verifySha256Base64(
                    respCanonical, respSig, RsaUtil.encodePublicKeyBase64(SERVER_KP.getPublic())));
        }

        @Test
        @DisplayName("GET /api/open/merchant/query-order 跨路径重放相同签名被 401 拒绝")
        void testCrossUriReplayBlocked() {
            String ts = String.valueOf(System.currentTimeMillis());
            String nonce = "e2e-rsa-cross-" + System.nanoTime();
            // 故意按另一个 URI 签名
            String forgedUriCanonical = "GET\n/api/open/merchant/other\n" + ts + "\n" + nonce
                    + "\norderId=ORD-CROSS\n" + DigestUtil.sha256Hex("");
            String sig = RsaUtil.signSha256Base64(
                    forgedUriCanonical, RsaUtil.encodePrivateKeyBase64(CLIENT_KP.getPrivate()));

            webTestClient.get()
                    .uri("/api/open/merchant/query-order?orderId=ORD-CROSS")
                    .header(GatewayConstants.X_APP_ID, "rsa-merchant")
                    .header(GatewayConstants.X_TIMESTAMP, ts)
                    .header(GatewayConstants.X_NONCE, nonce)
                    .header(GatewayConstants.X_SIGNATURE, sig)
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(401);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "ateng.cloud.gateway.sign.canonical-mode=STANDARD",
                    "ateng.cloud.gateway.sign.algorithm=SM2",
                    "ateng.cloud.gateway.sign.sign-response=true",
                    "spring.cloud.nacos.discovery.enabled=false",
                    "spring.cloud.nacos.config.enabled=false"
            }
    )
    @AutoConfigureWebTestClient(timeout = "15000")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @DisplayName("STANDARD + SM2 国密双向加验签端到端闭环")
    class Sm2StandardSignTests {

        private static final Sm2Util.Sm2KeyPair CLIENT_KP = Sm2Util.generateKeyPair();
        private static final Sm2Util.Sm2KeyPair SERVER_KP = Sm2Util.generateKeyPair();

        @DynamicPropertySource
        static void registerSm2Keys(DynamicPropertyRegistry registry) {
            registry.add("ateng.cloud.gateway.sign.sm2-public-key", CLIENT_KP::publicKeyBase64);
            registry.add("ateng.cloud.gateway.sign.sm2-private-key", SERVER_KP::privateKeyBase64);
        }

        @Autowired
        private WebTestClient webTestClient;

        @Test
        @DisplayName("GET /api/open/merchant/query-order 国密 SM2 验签与出站响应加签回写通过")
        void testStandardSm2QueryOrderAndResponseSign() {
            String ts = String.valueOf(System.currentTimeMillis());
            String nonce = "e2e-sm2-" + System.nanoTime();
            String canonical = "GET\n/api/open/merchant/query-order\n" + ts + "\n" + nonce
                    + "\norderId=ORD-SM2-001\n" + DigestUtil.sha256Hex("");
            String sig = Sm2Util.signBase64(canonical, CLIENT_KP.privateKeyHex());

            EntityExchangeResult<byte[]> result = webTestClient.get()
                    .uri("/api/open/merchant/query-order?orderId=ORD-SM2-001")
                    .header(GatewayConstants.X_APP_ID, "sm2-merchant")
                    .header(GatewayConstants.X_TIMESTAMP, ts)
                    .header(GatewayConstants.X_NONCE, nonce)
                    .header(GatewayConstants.X_SIGNATURE, sig)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().exists(GatewayConstants.X_SIGNATURE)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200)
                    .jsonPath("$.data.orderId").isEqualTo("ORD-SM2-001")
                    .returnResult();

            String respSig = result.getResponseHeaders().getFirst(GatewayConstants.X_SIGNATURE);
            assertNotNull(respSig);
            String respCanonical = ts + "\n" + nonce + "\n" + DigestUtil.sha256Hex(result.getResponseBody());
            assertTrue(Sm2Util.verifyBase64(respCanonical, respSig, SERVER_KP.publicKeyHex()),
                    "SM2 出站响应签名验证必须通过");
            String bodyStr = new String(result.getResponseBody(), StandardCharsets.UTF_8);
            assertTrue(bodyStr.contains("ORD-SM2-001"));
        }
    }
}
