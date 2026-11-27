package io.github.atengk.cloud.sample.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.RsaUtil;
import io.github.atengk.boot.core.util.Sm2Util;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.crypto.cipher.RsaAesGcmGatewayCipher;
import io.github.atengk.cloud.gateway.crypto.cipher.Sm2Sm4GcmGatewayCipher;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherInput;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherResult;
import io.github.atengk.cloud.gateway.crypto.model.GatewayEncryptedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SampleGatewayApplication} 网关边缘混合加解密、SPA 会话密钥与 Encrypt-then-Sign 端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
@DisplayName("网关边缘混合加解密与 Encrypt-then-Sign 端到端集成测试")
class SampleGatewayCryptoIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RsaAesGcmGatewayCipher RSA_CIPHER = new RsaAesGcmGatewayCipher();
    private static final Sm2Sm4GcmGatewayCipher SM2_CIPHER = new Sm2Sm4GcmGatewayCipher();

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "ateng.cloud.gateway.sign.enabled=false",
                    "ateng.cloud.gateway.crypto.enabled=true",
                    "ateng.cloud.gateway.crypto.algorithm=RSA_AES_GCM",
                    "ateng.cloud.gateway.crypto.response-key-mode=CLIENT_PUBLIC_KEY",
                    "ateng.cloud.gateway.crypto.active-key-id=v1",
                    "ateng.cloud.gateway.crypto.max-body-size=4KB",
                    "ateng.cloud.gateway.crypto.include-paths[0]=/api/crypto/**",
                    "spring.cloud.gateway.globalcors.cors-configurations[/**].allowed-origin-patterns[0]=https://spa.example.com",
                    "spring.cloud.gateway.globalcors.cors-configurations[/**].allowed-methods[0]=*",
                    "spring.cloud.gateway.globalcors.cors-configurations[/**].allowed-headers[0]=*",
                    "spring.cloud.nacos.discovery.enabled=false",
                    "spring.cloud.nacos.config.enabled=false"
            }
    )
    @AutoConfigureWebTestClient(timeout = "15000")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @DisplayName("RSA-AES-GCM 双模加解密、CORS Expose-Headers、400/413 与 SSE 放行测试")
    class RsaClientPublicKeyTests {

        private static final KeyPair SERVER_KP = RsaUtil.generateKeyPair(2048);
        private static final KeyPair CLIENT_KP = RsaUtil.generateKeyPair(2048);

        @DynamicPropertySource
        static void registerKeys(DynamicPropertyRegistry registry) {
            registry.add("ateng.cloud.gateway.crypto.rsa-server-private-key",
                    () -> RsaUtil.encodePrivateKeyBase64(SERVER_KP.getPrivate()));
            registry.add("ateng.cloud.gateway.crypto.rsa-client-public-key",
                    () -> RsaUtil.encodePublicKeyBase64(CLIENT_KP.getPublic()));
        }

        @Autowired
        private WebTestClient webTestClient;

        @LocalServerPort
        private int port;

        @Test
        @DisplayName("POST /api/crypto/checkout 入站解密、剥离加密头保留 X-App-Id、出站加密并自动暴露 CORS 标头")
        void testRsaCheckoutRoundTripWithCorsExpose() throws Exception {
            String plainReq = "{\"orderNo\":\"ORD-CRYPTO-1\",\"amountCents\":12800,\"remark\":\"VIP\"}";
            GatewayCipherResult reqEnc = RSA_CIPHER.encrypt(
                    plainReq.getBytes(StandardCharsets.UTF_8),
                    RsaUtil.encodePublicKeyBase64(SERVER_KP.getPublic()),
                    null);
            String reqEnvelope = OBJECT_MAPPER.writeValueAsString(
                    new GatewayEncryptedPayload(Base64.getEncoder().encodeToString(reqEnc.cipherBytes())));

            WebTestClient nettyClient = WebTestClient.bindToServer()
                    .baseUrl("http://localhost:" + port)
                    .build();

            EntityExchangeResult<byte[]> result = nettyClient.post()
                    .uri("/api/crypto/checkout")
                    .header(HttpHeaders.ORIGIN, "https://spa.example.com")
                    .header(GatewayConstants.X_APP_ID, "app-mall")
                    .header(GatewayConstants.X_ENCRYPT_KEY, reqEnc.encryptedKey())
                    .header(GatewayConstants.X_ENCRYPT_IV, reqEnc.iv())
                    .header(GatewayConstants.X_KEY_ID, "v1")
                    .header(GatewayConstants.X_ENCRYPT_ALG, "RSA_AES_GCM")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(reqEnvelope)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().exists(GatewayConstants.X_ENCRYPT_KEY)
                    .expectHeader().exists(GatewayConstants.X_ENCRYPT_IV)
                    .expectBody()
                    .returnResult();

            HttpHeaders headers = result.getResponseHeaders();
            String expose = headers.getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
            assertNotNull(expose);
            assertTrue(expose.contains(GatewayConstants.X_ENCRYPT_KEY));
            assertTrue(expose.contains(GatewayConstants.X_ENCRYPT_IV));
            assertTrue(expose.contains(GatewayConstants.X_KEY_ID));
            assertTrue(expose.contains(GatewayConstants.X_ENCRYPT_ALG));

            GatewayEncryptedPayload outPayload = OBJECT_MAPPER.readValue(
                    result.getResponseBody(), GatewayEncryptedPayload.class);
            byte[] plainRespBytes = RSA_CIPHER.decrypt(
                    new GatewayCipherInput(
                            Base64.getDecoder().decode(outPayload.getCipherText()),
                            headers.getFirst(GatewayConstants.X_ENCRYPT_KEY),
                            headers.getFirst(GatewayConstants.X_ENCRYPT_IV),
                            null),
                    RsaUtil.encodePrivateKeyBase64(CLIENT_KP.getPrivate()));

            String respJson = new String(plainRespBytes, StandardCharsets.UTF_8);
            assertTrue(respJson.contains("\"code\":200"));
            assertTrue(respJson.contains("\"orderNo\":\"ORD-CRYPTO-1\""));
            assertTrue(respJson.contains("\"receivedAppId\":\"app-mall\""));
            assertTrue(respJson.contains("\"encryptHeaderVisibleToDownstream\":false"));
        }

        @Test
        @DisplayName("篡改密文返回 HTTP 400、超限报文返回 HTTP 413 且 SSE 流式端点自动跳过加密")
        void testBadCipher400PayloadTooLarge413AndSseBypass() throws Exception {
            String plainReq = "{\"orderNo\":\"ORD-BAD\",\"amountCents\":100}";
            GatewayCipherResult reqEnc = RSA_CIPHER.encrypt(
                    plainReq.getBytes(StandardCharsets.UTF_8),
                    RsaUtil.encodePublicKeyBase64(SERVER_KP.getPublic()),
                    null);
            byte[] tampered = reqEnc.cipherBytes().clone();
            tampered[tampered.length - 1] ^= 0x0F;

            webTestClient.post()
                    .uri("/api/crypto/checkout")
                    .header(GatewayConstants.X_ENCRYPT_KEY, reqEnc.encryptedKey())
                    .header(GatewayConstants.X_ENCRYPT_IV, reqEnc.iv())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(OBJECT_MAPPER.writeValueAsString(
                            new GatewayEncryptedPayload(Base64.getEncoder().encodeToString(tampered))))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(400);

            String oversized = "{\"cipherText\":\"" + "A".repeat(5000) + "\"}";
            webTestClient.post()
                    .uri("/api/crypto/checkout")
                    .header(GatewayConstants.X_ENCRYPT_KEY, reqEnc.encryptedKey())
                    .header(GatewayConstants.X_ENCRYPT_IV, reqEnc.iv())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(oversized)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(413);

            EntityExchangeResult<String> sseResult = webTestClient.get()
                    .uri("/api/crypto/stream/events")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class)
                    .returnResult();
            assertFalse(sseResult.getResponseHeaders().containsKey(GatewayConstants.X_ENCRYPT_KEY));
            assertTrue(sseResult.getResponseBody().contains("crypto-sse-bypass-1"));
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "ateng.cloud.gateway.sign.enabled=false",
                    "ateng.cloud.gateway.crypto.enabled=true",
                    "ateng.cloud.gateway.crypto.algorithm=RSA_AES_GCM",
                    "ateng.cloud.gateway.crypto.response-key-mode=REQUEST_SESSION_KEY",
                    "ateng.cloud.gateway.crypto.include-paths[0]=/api/crypto/**",
                    "spring.cloud.nacos.discovery.enabled=false",
                    "spring.cloud.nacos.config.enabled=false"
            }
    )
    @AutoConfigureWebTestClient(timeout = "15000")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @DisplayName("SPA REQUEST_SESSION_KEY 无静态私钥模式 GET 握手集成测试")
    class SpaRequestSessionKeyTests {

        private static final KeyPair SERVER_KP = RsaUtil.generateKeyPair(2048);

        @DynamicPropertySource
        static void registerKeys(DynamicPropertyRegistry registry) {
            registry.add("ateng.cloud.gateway.crypto.rsa-server-private-key",
                    () -> RsaUtil.encodePrivateKeyBase64(SERVER_KP.getPrivate()));
        }

        @Autowired
        private WebTestClient webTestClient;

        @Test
        @DisplayName("GET /api/crypto/spa/profile 携带 X-Encrypt-Key 复用会话密钥加密响应；缺头 Fail-Closed 400")
        void testSpaGetHandshakeAndMissingHeaderFailClosed() throws Exception {
            byte[] sessionKey = CryptoUtil.generateKey(256);
            String wrappedKey = Base64.getEncoder().encodeToString(
                    RsaUtil.encryptOaep(sessionKey, SERVER_KP.getPublic()));

            EntityExchangeResult<byte[]> result = webTestClient.get()
                    .uri("/api/crypto/spa/profile")
                    .header(GatewayConstants.X_ENCRYPT_KEY, wrappedKey)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().doesNotExist(GatewayConstants.X_ENCRYPT_KEY)
                    .expectHeader().exists(GatewayConstants.X_ENCRYPT_IV)
                    .expectBody()
                    .returnResult();

            GatewayEncryptedPayload outPayload = OBJECT_MAPPER.readValue(
                    result.getResponseBody(), GatewayEncryptedPayload.class);
            String iv = result.getResponseHeaders().getFirst(GatewayConstants.X_ENCRYPT_IV);
            byte[] plainBytes = CryptoUtil.decryptGcm(
                    Base64.getDecoder().decode(outPayload.getCipherText()),
                    sessionKey,
                    Base64.getDecoder().decode(iv));
            String respJson = new String(plainBytes, StandardCharsets.UTF_8);
            assertTrue(respJson.contains("\"displayName\":\"Ateng-Architect\""));
            assertTrue(respJson.contains("138****8888"));

            webTestClient.get()
                    .uri("/api/crypto/spa/profile")
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(400);

            // 有体 POST + REQUEST_SESSION_KEY：入站解封与解密 -> 出站复用同一会话密钥 + 新随机 IV 且不回传 X-Encrypt-Key
            byte[] postSessionKey = CryptoUtil.generateKey(256);
            byte[] postReqIv = CryptoUtil.generateGcmIv();
            String reqIvB64 = Base64.getEncoder().encodeToString(postReqIv);
            String postWrappedKey = Base64.getEncoder().encodeToString(
                    RsaUtil.encryptOaep(postSessionKey, SERVER_KP.getPublic()));
            String plainCheckout = "{\"orderNo\":\"ORD-SPA-POST-1\",\"amountCents\":8800,\"remark\":\"SPA\"}";
            byte[] reqCipherBytes = CryptoUtil.encryptGcm(
                    plainCheckout.getBytes(StandardCharsets.UTF_8), postSessionKey, postReqIv);
            String postEnvelope = OBJECT_MAPPER.writeValueAsString(
                    new GatewayEncryptedPayload(Base64.getEncoder().encodeToString(reqCipherBytes)));

            EntityExchangeResult<byte[]> postResult = webTestClient.post()
                    .uri("/api/crypto/checkout")
                    .header(GatewayConstants.X_APP_ID, "spa-web")
                    .header(GatewayConstants.X_ENCRYPT_KEY, postWrappedKey)
                    .header(GatewayConstants.X_ENCRYPT_IV, reqIvB64)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(postEnvelope)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().doesNotExist(GatewayConstants.X_ENCRYPT_KEY)
                    .expectHeader().exists(GatewayConstants.X_ENCRYPT_IV)
                    .expectBody()
                    .returnResult();

            String postRespIv = postResult.getResponseHeaders().getFirst(GatewayConstants.X_ENCRYPT_IV);
            GatewayEncryptedPayload postOutPayload = OBJECT_MAPPER.readValue(
                    postResult.getResponseBody(), GatewayEncryptedPayload.class);
            String decryptedPostResp = new String(CryptoUtil.decryptGcm(
                    Base64.getDecoder().decode(postOutPayload.getCipherText()),
                    postSessionKey,
                    Base64.getDecoder().decode(postRespIv)), StandardCharsets.UTF_8);
            assertTrue(decryptedPostResp.contains("\"orderNo\":\"ORD-SPA-POST-1\""));
            assertTrue(decryptedPostResp.contains("\"receivedAppId\":\"spa-web\""));

            // SSE GET (Accept: text/event-stream) 在 REQUEST_SESSION_KEY + required=true 下直通放行
            EntityExchangeResult<String> spaSse = webTestClient.get()
                    .uri("/api/crypto/stream/events")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class)
                    .returnResult();
            assertTrue(spaSse.getResponseBody().contains("crypto-sse-bypass-1"));
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "ateng.cloud.gateway.sign.enabled=true",
                    "ateng.cloud.gateway.sign.canonical-mode=STANDARD",
                    "ateng.cloud.gateway.sign.algorithm=RSA",
                    "ateng.cloud.gateway.sign.sign-response=true",
                    "ateng.cloud.gateway.sign.include-paths[0]=/api/crypto/**",
                    "ateng.cloud.gateway.crypto.enabled=true",
                    "ateng.cloud.gateway.crypto.algorithm=SM2_SM4_GCM",
                    "ateng.cloud.gateway.crypto.response-key-mode=CLIENT_PUBLIC_KEY",
                    "ateng.cloud.gateway.crypto.active-key-id=v1",
                    "ateng.cloud.gateway.crypto.bind-aad=true",
                    "ateng.cloud.gateway.crypto.keys.v1.enabled=true",
                    "ateng.cloud.gateway.crypto.keys.v2.enabled=false",
                    "ateng.cloud.gateway.crypto.include-paths[0]=/api/crypto/**",
                    "spring.cloud.nacos.discovery.enabled=false",
                    "spring.cloud.nacos.config.enabled=false"
            }
    )
    @AutoConfigureWebTestClient(timeout = "15000")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @DisplayName("SM2-SM4-GCM + AAD + 多版本密钥轮转 + Encrypt-then-Sign 端到端集成测试")
    class Sm2KeyRotationAndEncryptThenSignTests {

        private static final KeyPair CLIENT_SIGN_KP = RsaUtil.generateKeyPair(2048);
        private static final KeyPair GATEWAY_SIGN_KP = RsaUtil.generateKeyPair(2048);
        private static final Sm2Util.Sm2KeyPair SERVER_CRYPTO_V1 = Sm2Util.generateKeyPair();
        private static final Sm2Util.Sm2KeyPair CLIENT_CRYPTO_V1 = Sm2Util.generateKeyPair();
        private static final Sm2Util.Sm2KeyPair SERVER_CRYPTO_V2 = Sm2Util.generateKeyPair();
        private static final Sm2Util.Sm2KeyPair CLIENT_CRYPTO_V2 = Sm2Util.generateKeyPair();

        @DynamicPropertySource
        static void registerKeys(DynamicPropertyRegistry registry) {
            registry.add("ateng.cloud.gateway.sign.apps.partner-crypto.rsa-public-key",
                    () -> RsaUtil.encodePublicKeyBase64(CLIENT_SIGN_KP.getPublic()));
            registry.add("ateng.cloud.gateway.sign.rsa-private-key",
                    () -> RsaUtil.encodePrivateKeyBase64(GATEWAY_SIGN_KP.getPrivate()));

            registry.add("ateng.cloud.gateway.crypto.keys.v1.sm2-server-private-key",
                    SERVER_CRYPTO_V1::privateKeyBase64);
            registry.add("ateng.cloud.gateway.crypto.keys.v1.sm2-client-public-key",
                    CLIENT_CRYPTO_V1::publicKeyBase64);
            registry.add("ateng.cloud.gateway.crypto.keys.v2.sm2-server-private-key",
                    SERVER_CRYPTO_V2::privateKeyBase64);
            registry.add("ateng.cloud.gateway.crypto.keys.v2.sm2-client-public-key",
                    CLIENT_CRYPTO_V2::publicKeyBase64);
        }

        @Autowired
        private WebTestClient webTestClient;

        @Test
        @DisplayName("Encrypt-then-Sign 双向闭环：外层签密文、内层 SM2+AAD 解密与禁用 keyId=v2 拦截")
        void testEncryptThenSignAndKeyRotationDisabledVersion() throws Exception {
            String plainReq = "{\"orderNo\":\"ORD-SM2-AAD-1\",\"amountCents\":6600,\"remark\":\"SM2\"}";
            byte[] aadV1 = "partner-crypto|v1|SM2_SM4_GCM".getBytes(StandardCharsets.UTF_8);
            GatewayCipherResult encV1 = SM2_CIPHER.encrypt(
                    plainReq.getBytes(StandardCharsets.UTF_8),
                    SERVER_CRYPTO_V1.publicKeyBase64(),
                    aadV1);
            String cipherJsonBody = OBJECT_MAPPER.writeValueAsString(
                    new GatewayEncryptedPayload(Base64.getEncoder().encodeToString(encV1.cipherBytes())));

            String ts = String.valueOf(System.currentTimeMillis());
            String nonce = "sm2-ets-" + System.nanoTime();
            String reqCanonical = "POST\n/api/crypto/checkout\n" + ts + "\n" + nonce + "\n\n"
                    + DigestUtil.sha256Hex(cipherJsonBody);
            String reqSig = RsaUtil.signSha256Base64(
                    reqCanonical, RsaUtil.encodePrivateKeyBase64(CLIENT_SIGN_KP.getPrivate()));

            EntityExchangeResult<byte[]> result = webTestClient.post()
                    .uri("/api/crypto/checkout")
                    .header(GatewayConstants.X_APP_ID, "partner-crypto")
                    .header(GatewayConstants.X_TIMESTAMP, ts)
                    .header(GatewayConstants.X_NONCE, nonce)
                    .header(GatewayConstants.X_SIGNATURE, reqSig)
                    .header(GatewayConstants.X_ENCRYPT_KEY, encV1.encryptedKey())
                    .header(GatewayConstants.X_ENCRYPT_IV, encV1.iv())
                    .header(GatewayConstants.X_KEY_ID, "v1")
                    .header(GatewayConstants.X_ENCRYPT_ALG, "SM2_SM4_GCM")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(cipherJsonBody)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().exists(GatewayConstants.X_SIGNATURE)
                    .expectHeader().exists(GatewayConstants.X_ENCRYPT_KEY)
                    .expectBody()
                    .returnResult();

            HttpHeaders outHeaders = result.getResponseHeaders();
            String respBodyStr = new String(result.getResponseBody(), StandardCharsets.UTF_8);
            String respCanonical = outHeaders.getFirst(GatewayConstants.X_TIMESTAMP) + "\n"
                    + outHeaders.getFirst(GatewayConstants.X_NONCE) + "\n"
                    + DigestUtil.sha256Hex(respBodyStr);
            assertTrue(RsaUtil.verifySha256Base64(
                    respCanonical,
                    outHeaders.getFirst(GatewayConstants.X_SIGNATURE),
                    RsaUtil.encodePublicKeyBase64(GATEWAY_SIGN_KP.getPublic())));

            GatewayEncryptedPayload outPayload = OBJECT_MAPPER.readValue(respBodyStr, GatewayEncryptedPayload.class);
            byte[] respPlain = SM2_CIPHER.decrypt(
                    new GatewayCipherInput(
                            Base64.getDecoder().decode(outPayload.getCipherText()),
                            outHeaders.getFirst(GatewayConstants.X_ENCRYPT_KEY),
                            outHeaders.getFirst(GatewayConstants.X_ENCRYPT_IV),
                            aadV1),
                    CLIENT_CRYPTO_V1.privateKeyBase64());
            String respPlainStr = new String(respPlain, StandardCharsets.UTF_8);
            assertTrue(respPlainStr.contains("\"orderNo\":\"ORD-SM2-AAD-1\""));
            assertTrue(respPlainStr.contains("\"receivedAppId\":\"partner-crypto\""));

            // 请求使用已禁用版本 v2（即使合法签名）应穿透 SignGuard (-101) 被 Crypto (-95) 拦截返回 400
            String nonceV2 = "sm2-v2-disabled-" + System.nanoTime();
            String canonicalV2 = "POST\n/api/crypto/checkout\n" + ts + "\n" + nonceV2 + "\n\n"
                    + DigestUtil.sha256Hex(cipherJsonBody);
            String sigV2 = RsaUtil.signSha256Base64(
                    canonicalV2, RsaUtil.encodePrivateKeyBase64(CLIENT_SIGN_KP.getPrivate()));

            webTestClient.post()
                    .uri("/api/crypto/checkout")
                    .header(GatewayConstants.X_APP_ID, "partner-crypto")
                    .header(GatewayConstants.X_TIMESTAMP, ts)
                    .header(GatewayConstants.X_NONCE, nonceV2)
                    .header(GatewayConstants.X_SIGNATURE, sigV2)
                    .header(GatewayConstants.X_ENCRYPT_KEY, encV1.encryptedKey())
                    .header(GatewayConstants.X_ENCRYPT_IV, encV1.iv())
                    .header(GatewayConstants.X_KEY_ID, "v2")
                    .header(GatewayConstants.X_ENCRYPT_ALG, "SM2_SM4_GCM")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(cipherJsonBody)
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(400);
        }
    }
}
