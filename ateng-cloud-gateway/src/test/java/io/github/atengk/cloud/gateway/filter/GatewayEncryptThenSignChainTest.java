package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.RsaUtil;
import io.github.atengk.boot.core.util.Sm2Util;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.crypto.cipher.RsaAesGcmGatewayCipher;
import io.github.atengk.cloud.gateway.crypto.cipher.Sm2Sm4GcmGatewayCipher;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoResponseKeyMode;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherInput;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherResult;
import io.github.atengk.cloud.gateway.crypto.model.GatewayEncryptedPayload;
import io.github.atengk.cloud.gateway.properties.AtengGatewayCryptoProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import io.github.atengk.cloud.gateway.provider.InMemoryReactiveGatewayNonceValidator;
import io.github.atengk.cloud.gateway.provider.PropertiesGatewayCryptoKeyProvider;
import io.github.atengk.cloud.gateway.provider.PropertiesGatewaySignKeyProvider;
import io.github.atengk.cloud.gateway.sign.enums.GatewaySignAlgorithm;
import io.github.atengk.cloud.gateway.sign.enums.GatewaySignCanonicalMode;
import io.github.atengk.cloud.gateway.sign.signer.HmacSha256GatewaySigner;
import io.github.atengk.cloud.gateway.sign.signer.RsaGatewaySigner;
import io.github.atengk.cloud.gateway.sign.signer.Sm2GatewaySigner;
import io.github.atengk.cloud.gateway.sign.util.GatewaySignCanonicalUtil;
import io.github.atengk.cloud.gateway.spi.GatewayCryptoKeyProvider;
import io.github.atengk.cloud.gateway.spi.SignSecretProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.OrderComparator;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link GatewaySignGuardFilter} (-101) 与 {@link GatewayCryptoFilter} (-95)
 * 物理级 Encrypt-then-Sign 串联协作链路单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
class GatewayEncryptThenSignChainTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Sm2Sm4GcmGatewayCipher sm2Cipher = new Sm2Sm4GcmGatewayCipher();
    private final RsaGatewaySigner rsaSigner = new RsaGatewaySigner();

    @Test
    @DisplayName("入站先验密文签名后解密，出站先加密后回签，篡改密文即被外层 401 短路拦截")
    void shouldEnforceEncryptThenSignEndToEndChain() throws Exception {
        KeyPair clientSignKp = RsaUtil.generateKeyPair();
        KeyPair gatewaySignKp = RsaUtil.generateKeyPair();
        Sm2Util.Sm2KeyPair serverCryptoKp = Sm2Util.generateKeyPair();
        Sm2Util.Sm2KeyPair clientCryptoKp = Sm2Util.generateKeyPair();

        // 1. 装配外层签名守卫 (-101): STANDARD + RSA + signResponse=true
        AtengGatewaySignProperties signProps = new AtengGatewaySignProperties();
        signProps.setEnabled(true);
        signProps.setCanonicalMode(GatewaySignCanonicalMode.STANDARD);
        signProps.setAlgorithm(GatewaySignAlgorithm.RSA);
        signProps.setSignResponse(true);
        signProps.setPublicKey(RsaUtil.encodePublicKeyBase64(clientSignKp.getPublic()));
        signProps.setPrivateKey(RsaUtil.encodePrivateKeyBase64(gatewaySignKp.getPrivate()));
        signProps.setIncludePaths(List.of("/api/secure/**"));

        GatewaySignGuardFilter signFilter = new GatewaySignGuardFilter(
                signProps,
                new PropertiesGatewaySignKeyProvider(signProps, (SignSecretProvider) null),
                new InMemoryReactiveGatewayNonceValidator(),
                List.of(new HmacSha256GatewaySigner(), rsaSigner, new Sm2GatewaySigner()),
                objectMapper
        );

        // 2. 装配内层加解密守卫 (-95): SM2_SM4_GCM + bindAad=true
        AtengGatewayCryptoProperties cryptoProps = new AtengGatewayCryptoProperties();
        cryptoProps.setEnabled(true);
        cryptoProps.setAlgorithm(GatewayCryptoAlgorithm.SM2_SM4_GCM);
        cryptoProps.setResponseKeyMode(GatewayCryptoResponseKeyMode.CLIENT_PUBLIC_KEY);
        cryptoProps.setActiveKeyId("k1");
        cryptoProps.setBindAad(true);
        cryptoProps.setSm2ServerPrivateKey(serverCryptoKp.privateKeyBase64());
        cryptoProps.setSm2ClientPublicKey(clientCryptoKp.publicKeyBase64());
        cryptoProps.setIncludePaths(List.of("/api/secure/**"));

        GatewayCryptoKeyProvider realKeyProvider = new PropertiesGatewayCryptoKeyProvider(cryptoProps);
        GatewayCryptoFilter cryptoFilter = new GatewayCryptoFilter(
                cryptoProps,
                realKeyProvider,
                List.of(new RsaAesGcmGatewayCipher(), sm2Cipher),
                objectMapper
        );

        assertTrue(signFilter.getOrder() < cryptoFilter.getOrder());

        // 按 Spring OrderComparator 严格排序组装过滤链
        List<GlobalFilter> orderedFilters = new java.util.ArrayList<>(List.of(cryptoFilter, signFilter));
        OrderComparator.sort(orderedFilters);
        assertEquals(GatewaySignGuardFilter.class, orderedFilters.get(0).getClass());
        assertEquals(GatewayCryptoFilter.class, orderedFilters.get(1).getClass());

        // 客户端先加密请求体（SM2-SM4-GCM + AAD），再对密文体与标头签名（RSA STANDARD）
        String reqPlain = "{\"transfer\":\"USD-500\"}";
        byte[] aad = "partner-01|k1|SM2_SM4_GCM".getBytes(StandardCharsets.UTF_8);
        GatewayCipherResult reqCipher = sm2Cipher.encrypt(
                reqPlain.getBytes(StandardCharsets.UTF_8),
                serverCryptoKp.publicKeyBase64(),
                aad);
        String cipherJsonBody = objectMapper.writeValueAsString(
                new GatewayEncryptedPayload(Base64.getEncoder().encodeToString(reqCipher.cipherBytes())));

        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = "chain-nonce-1";
        String canonicalReq = GatewaySignCanonicalUtil.buildStandardRequestCanonicalString(
                "POST", "/api/secure/pay", ts, nonce, null, DigestUtil.sha256Hex(cipherJsonBody));
        String reqSig = rsaSigner.sign(canonicalReq, RsaUtil.encodePrivateKeyBase64(clientSignKp.getPrivate()));

        MockServerHttpRequest validReq = MockServerHttpRequest.post("/api/secure/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayConstants.X_APP_ID, "partner-01")
                .header(GatewayConstants.X_TIMESTAMP, ts)
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, reqSig)
                .header(GatewayConstants.X_ENCRYPT_KEY, reqCipher.encryptedKey())
                .header(GatewayConstants.X_ENCRYPT_IV, reqCipher.iv())
                .header(GatewayConstants.X_KEY_ID, "k1")
                .header(GatewayConstants.X_ENCRYPT_ALG, "SM2_SM4_GCM")
                .body(cipherJsonBody);
        MockServerWebExchange validExchange = MockServerWebExchange.from(validReq);

        AtomicReference<String> downstreamPlain = new AtomicReference<>();
        String respPlain = "{\"txStatus\":\"SETTLED\"}";
        executeChain(orderedFilters, 0, validExchange, ex ->
                DataBufferUtils.join(ex.getRequest().getBody())
                        .flatMap(buf -> {
                            byte[] b = new byte[buf.readableByteCount()];
                            buf.read(b);
                            DataBufferUtils.release(buf);
                            downstreamPlain.set(new String(b, StandardCharsets.UTF_8));
                            ex.getResponse().setStatusCode(HttpStatus.OK);
                            ex.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            return ex.getResponse().writeWith(Mono.just(
                                    ex.getResponse().bufferFactory().wrap(respPlain.getBytes(StandardCharsets.UTF_8))));
                        })).block();

        assertEquals(reqPlain, downstreamPlain.get());
        assertEquals(HttpStatus.OK, validExchange.getResponse().getStatusCode());

        // 验证响应 Encrypt-then-Sign：先用网关公钥校验密文响应的 X-Signature，再用客户端 SM2 私钥解密密文体
        HttpHeaders outHeaders = validExchange.getResponse().getHeaders();
        String outCipherBody = validExchange.getResponse().getBodyAsString().block();
        String outSig = outHeaders.getFirst(GatewayConstants.X_SIGNATURE);
        String outTs = outHeaders.getFirst(GatewayConstants.X_TIMESTAMP);
        String outNonce = outHeaders.getFirst(GatewayConstants.X_NONCE);
        assertNotNull(outSig);
        assertNotNull(outHeaders.getFirst(GatewayConstants.X_ENCRYPT_KEY));

        String outCanonical = GatewaySignCanonicalUtil.buildResponseCanonicalString(
                outTs, outNonce, DigestUtil.sha256Hex(outCipherBody));
        assertTrue(rsaSigner.verify(outCanonical, outSig, RsaUtil.encodePublicKeyBase64(gatewaySignKp.getPublic())));

        GatewayEncryptedPayload outPayload = objectMapper.readValue(outCipherBody, GatewayEncryptedPayload.class);
        byte[] decryptedResp = sm2Cipher.decrypt(
                new GatewayCipherInput(
                        Base64.getDecoder().decode(outPayload.getCipherText()),
                        outHeaders.getFirst(GatewayConstants.X_ENCRYPT_KEY),
                        outHeaders.getFirst(GatewayConstants.X_ENCRYPT_IV),
                        aad),
                clientCryptoKp.privateKeyBase64());
        assertEquals(respPlain, new String(decryptedResp, StandardCharsets.UTF_8));

        // 3. 对抗验证：篡改密文体一字节但不重签，外层 SignGuard (-101) 必须在解密前 401 阻断，内层零解密调用
        GatewayCryptoKeyProvider spyKeyProvider = Mockito.mock(GatewayCryptoKeyProvider.class);
        GatewayCryptoFilter guardedCrypto = new GatewayCryptoFilter(cryptoProps, spyKeyProvider, objectMapper);
        List<GlobalFilter> guardedChain = new java.util.ArrayList<>(List.of(signFilter, guardedCrypto));
        OrderComparator.sort(guardedChain);

        byte[] tamperedBytes = cipherJsonBody.getBytes(StandardCharsets.UTF_8);
        tamperedBytes[tamperedBytes.length - 5] ^= 0x01;
        String tamperedBody = new String(tamperedBytes, StandardCharsets.UTF_8);
        MockServerHttpRequest tamperedReq = MockServerHttpRequest.post("/api/secure/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayConstants.X_APP_ID, "partner-01")
                .header(GatewayConstants.X_TIMESTAMP, ts)
                .header(GatewayConstants.X_NONCE, "chain-nonce-2")
                .header(GatewayConstants.X_SIGNATURE, reqSig)
                .header(GatewayConstants.X_ENCRYPT_KEY, reqCipher.encryptedKey())
                .header(GatewayConstants.X_ENCRYPT_IV, reqCipher.iv())
                .header(GatewayConstants.X_KEY_ID, "k1")
                .body(tamperedBody);
        MockServerWebExchange tamperedExchange = MockServerWebExchange.from(tamperedReq);

        executeChain(guardedChain, 0, tamperedExchange, ex -> Mono.empty()).block();
        assertEquals(HttpStatus.UNAUTHORIZED, tamperedExchange.getResponse().getStatusCode());
        verifyNoInteractions(spyKeyProvider);

        // 4. 出站异常防线：外层 SignGuard 回签缺失私钥触发 500 时，必须剥离内层 Crypto 已写入的 X-Encrypt-* 响应头
        AtengGatewaySignProperties brokenSignProps = new AtengGatewaySignProperties();
        brokenSignProps.setEnabled(true);
        brokenSignProps.setCanonicalMode(GatewaySignCanonicalMode.STANDARD);
        brokenSignProps.setAlgorithm(GatewaySignAlgorithm.RSA);
        brokenSignProps.setSignResponse(true);
        brokenSignProps.setPublicKey(RsaUtil.encodePublicKeyBase64(clientSignKp.getPublic()));
        brokenSignProps.setIncludePaths(List.of("/api/secure/**"));
        GatewaySignGuardFilter brokenSignFilter = new GatewaySignGuardFilter(
                brokenSignProps,
                new PropertiesGatewaySignKeyProvider(brokenSignProps, (SignSecretProvider) null),
                new InMemoryReactiveGatewayNonceValidator(),
                List.of(new HmacSha256GatewaySigner(), rsaSigner, new Sm2GatewaySigner()),
                objectMapper
        );
        List<GlobalFilter> outboundFailChain = new java.util.ArrayList<>(List.of(cryptoFilter, brokenSignFilter));
        OrderComparator.sort(outboundFailChain);

        String outFailNonce = "chain-nonce-3";
        String outFailCanonical = GatewaySignCanonicalUtil.buildStandardRequestCanonicalString(
                "POST", "/api/secure/pay", ts, outFailNonce, null, DigestUtil.sha256Hex(cipherJsonBody));
        String outFailSig = rsaSigner.sign(outFailCanonical, RsaUtil.encodePrivateKeyBase64(clientSignKp.getPrivate()));

        MockServerHttpRequest outFailReq = MockServerHttpRequest.post("/api/secure/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayConstants.X_APP_ID, "partner-01")
                .header(GatewayConstants.X_TIMESTAMP, ts)
                .header(GatewayConstants.X_NONCE, outFailNonce)
                .header(GatewayConstants.X_SIGNATURE, outFailSig)
                .header(GatewayConstants.X_ENCRYPT_KEY, reqCipher.encryptedKey())
                .header(GatewayConstants.X_ENCRYPT_IV, reqCipher.iv())
                .header(GatewayConstants.X_KEY_ID, "k1")
                .header(GatewayConstants.X_ENCRYPT_ALG, "SM2_SM4_GCM")
                .body(cipherJsonBody);
        MockServerWebExchange outFailExchange = MockServerWebExchange.from(outFailReq);
        executeChain(outboundFailChain, 0, outFailExchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            ex.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return ex.getResponse().writeWith(Mono.just(
                    ex.getResponse().bufferFactory().wrap("{\"secret\":1}".getBytes(StandardCharsets.UTF_8))));
        }).block();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, outFailExchange.getResponse().getStatusCode());
        HttpHeaders errHeaders = outFailExchange.getResponse().getHeaders();
        org.junit.jupiter.api.Assertions.assertFalse(errHeaders.containsKey(GatewayConstants.X_ENCRYPT_KEY));
        org.junit.jupiter.api.Assertions.assertFalse(errHeaders.containsKey(GatewayConstants.X_ENCRYPT_IV));
        org.junit.jupiter.api.Assertions.assertFalse(errHeaders.containsKey(GatewayConstants.X_KEY_ID));
        org.junit.jupiter.api.Assertions.assertFalse(errHeaders.containsKey(GatewayConstants.X_ENCRYPT_ALG));
    }

    private Mono<Void> executeChain(List<GlobalFilter> filters,
                                    int index,
                                    ServerWebExchange exchange,
                                    GatewayFilterChain terminal) {
        if (index >= filters.size()) {
            return terminal.filter(exchange);
        }
        return filters.get(index).filter(exchange, ex -> executeChain(filters, index + 1, ex, terminal));
    }
}
