package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.RsaUtil;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.crypto.cipher.RsaAesGcmGatewayCipher;
import io.github.atengk.cloud.gateway.crypto.cipher.Sm2Sm4GcmGatewayCipher;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoResponseKeyMode;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherInput;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherResult;
import io.github.atengk.cloud.gateway.crypto.model.GatewayEncryptedPayload;
import io.github.atengk.cloud.gateway.properties.AtengGatewayCryptoProperties;
import io.github.atengk.cloud.gateway.provider.PropertiesGatewayCryptoKeyProvider;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GatewayCryptoFilter} 混合加解密、会话密钥握手与堆外内存零泄漏单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
class GatewayCryptoFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RsaAesGcmGatewayCipher rsaCipher = new RsaAesGcmGatewayCipher();

    private AtengGatewayCryptoProperties baseProperties(KeyPair serverKp, KeyPair clientKp) {
        AtengGatewayCryptoProperties props = new AtengGatewayCryptoProperties();
        props.setEnabled(true);
        props.setAlgorithm(GatewayCryptoAlgorithm.RSA_AES_GCM);
        props.setResponseKeyMode(GatewayCryptoResponseKeyMode.CLIENT_PUBLIC_KEY);
        props.setActiveKeyId("v1");
        props.setRsaServerPrivateKey(RsaUtil.encodePrivateKeyBase64(serverKp.getPrivate()));
        if (clientKp != null) {
            props.setRsaClientPublicKey(RsaUtil.encodePublicKeyBase64(clientKp.getPublic()));
        }
        props.setIncludePaths(List.of("/api/crypto/**"));
        return props;
    }

    private GatewayCryptoFilter createFilter(AtengGatewayCryptoProperties props) {
        return new GatewayCryptoFilter(
                props,
                new PropertiesGatewayCryptoKeyProvider(props),
                List.of(rsaCipher, new Sm2Sm4GcmGatewayCipher()),
                objectMapper
        );
    }

    @Test
    @DisplayName("默认关闭状态下直接放行请求且不改动请求体或响应体")
    void shouldPassThroughWhenDisabled() {
        AtengGatewayCryptoProperties props = new AtengGatewayCryptoProperties();
        assertFalse(props.isEnabled());

        GatewayCryptoFilter filter = createFilter(props);
        String plain = "{\"ping\":\"ok\"}";
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/crypto/ping")
                .contentType(MediaType.APPLICATION_JSON)
                .body(plain);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamSeen = new AtomicReference<>();
        filter.filter(exchange, ex -> DataBufferUtils.join(ex.getRequest().getBody())
                .flatMap(buf -> {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    DataBufferUtils.release(buf);
                    downstreamSeen.set(new String(bytes, StandardCharsets.UTF_8));
                    byte[] respBytes = "{\"pong\":true}".getBytes(StandardCharsets.UTF_8);
                    return ex.getResponse().writeWith(Mono.just(ex.getResponse().bufferFactory().wrap(respBytes)));
                })).block();

        assertEquals(plain, downstreamSeen.get());
        assertEquals("{\"pong\":true}", exchange.getResponse().getBodyAsString().block());
    }

    @Test
    @DisplayName("CLIENT_PUBLIC_KEY 全链路：入站解密 + 剥离加密头(保留X-App-Id) + 出站加密")
    void shouldDecryptInboundAndEncryptOutboundWithClientPublicKeyMode() throws Exception {
        KeyPair serverKp = RsaUtil.generateKeyPair();
        KeyPair clientKp = RsaUtil.generateKeyPair();
        AtengGatewayCryptoProperties props = baseProperties(serverKp, clientKp);
        GatewayCryptoFilter filter = createFilter(props);

        String plainReq = "{\"sku\":\"SKU-9527\",\"qty\":2}";
        GatewayCipherResult reqEnc = rsaCipher.encrypt(
                plainReq.getBytes(StandardCharsets.UTF_8),
                RsaUtil.encodePublicKeyBase64(serverKp.getPublic()),
                null);
        String envelopeReq = objectMapper.writeValueAsString(
                new GatewayEncryptedPayload(Base64.getEncoder().encodeToString(reqEnc.cipherBytes())));

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/crypto/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayConstants.X_APP_ID, "app-store")
                .header(GatewayConstants.X_ENCRYPT_KEY, reqEnc.encryptedKey())
                .header(GatewayConstants.X_ENCRYPT_IV, reqEnc.iv())
                .header(GatewayConstants.X_KEY_ID, "v1")
                .header(GatewayConstants.X_ENCRYPT_ALG, "RSA_AES_GCM")
                .body(envelopeReq);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamBody = new AtomicReference<>();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        String plainResp = "{\"accepted\":true,\"orderNo\":\"ORD-1\"}";

        filter.filter(exchange, ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return DataBufferUtils.join(ex.getRequest().getBody())
                    .flatMap(buf -> {
                        byte[] bytes = new byte[buf.readableByteCount()];
                        buf.read(bytes);
                        DataBufferUtils.release(buf);
                        downstreamBody.set(new String(bytes, StandardCharsets.UTF_8));
                        ex.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        byte[] respBytes = plainResp.getBytes(StandardCharsets.UTF_8);
                        return ex.getResponse().writeWith(Mono.just(ex.getResponse().bufferFactory().wrap(respBytes)));
                    });
        }).block();

        assertEquals(plainReq, downstreamBody.get());
        assertEquals("app-store", downstreamHeaders.get().getFirst(GatewayConstants.X_APP_ID));
        assertFalse(downstreamHeaders.get().containsKey(GatewayConstants.X_ENCRYPT_KEY));
        assertFalse(downstreamHeaders.get().containsKey(GatewayConstants.X_ENCRYPT_IV));
        assertFalse(downstreamHeaders.get().containsKey(GatewayConstants.X_KEY_ID));
        assertFalse(downstreamHeaders.get().containsKey(GatewayConstants.X_ENCRYPT_ALG));

        HttpHeaders outHeaders = exchange.getResponse().getHeaders();
        assertNotNull(outHeaders.getFirst(GatewayConstants.X_ENCRYPT_KEY));
        assertNotNull(outHeaders.getFirst(GatewayConstants.X_ENCRYPT_IV));
        assertEquals("v1", outHeaders.getFirst(GatewayConstants.X_KEY_ID));
        assertEquals("RSA_AES_GCM", outHeaders.getFirst(GatewayConstants.X_ENCRYPT_ALG));

        String outJson = exchange.getResponse().getBodyAsString().block();
        GatewayEncryptedPayload outPayload = objectMapper.readValue(outJson, GatewayEncryptedPayload.class);
        byte[] decryptedResp = rsaCipher.decrypt(
                new GatewayCipherInput(
                        Base64.getDecoder().decode(outPayload.getCipherText()),
                        outHeaders.getFirst(GatewayConstants.X_ENCRYPT_KEY),
                        outHeaders.getFirst(GatewayConstants.X_ENCRYPT_IV),
                        null),
                RsaUtil.encodePrivateKeyBase64(clientKp.getPrivate()));
        assertEquals(plainResp, new String(decryptedResp, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("SPA REQUEST_SESSION_KEY 无体 GET 握手复用会话密钥加密响应且不回传 X-Encrypt-Key；缺头 Fail-Closed 400")
    void shouldSupportSpaGetHandshakeAndFailClosedOnMissingKey() throws Exception {
        KeyPair serverKp = RsaUtil.generateKeyPair();
        AtengGatewayCryptoProperties props = baseProperties(serverKp, null);
        props.setResponseKeyMode(GatewayCryptoResponseKeyMode.REQUEST_SESSION_KEY);
        props.setBindAad(true);
        GatewayCryptoFilter filter = createFilter(props);

        // 1. 无体 GET 携带 X-Encrypt-Key 成功握手并回写 AES-GCM 密文
        byte[] spaSessionKey = CryptoUtil.generateKey(256);
        String wrappedKey = Base64.getEncoder().encodeToString(
                RsaUtil.encryptOaep(spaSessionKey, serverKp.getPublic()));
        MockServerHttpRequest getReq = MockServerHttpRequest.get("/api/crypto/spa/profile")
                .header(GatewayConstants.X_APP_ID, "web-spa")
                .header(GatewayConstants.X_ENCRYPT_KEY, wrappedKey)
                .build();
        MockServerWebExchange getExchange = MockServerWebExchange.from(getReq);

        String plainProfile = "{\"nickname\":\"Ateng\",\"role\":\"admin\"}";
        filter.filter(getExchange, ex -> {
            assertFalse(ex.getRequest().getHeaders().containsKey(GatewayConstants.X_ENCRYPT_KEY));
            ex.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return ex.getResponse().writeWith(Mono.just(
                    ex.getResponse().bufferFactory().wrap(plainProfile.getBytes(StandardCharsets.UTF_8))));
        }).block();

        HttpHeaders outHeaders = getExchange.getResponse().getHeaders();
        assertFalse(outHeaders.containsKey(GatewayConstants.X_ENCRYPT_KEY));
        assertNotNull(outHeaders.getFirst(GatewayConstants.X_ENCRYPT_IV));
        assertEquals("v1", outHeaders.getFirst(GatewayConstants.X_KEY_ID));

        String cipherJson = getExchange.getResponse().getBodyAsString().block();
        GatewayEncryptedPayload payload = objectMapper.readValue(cipherJson, GatewayEncryptedPayload.class);
        byte[] aad = "web-spa|v1|RSA_AES_GCM".getBytes(StandardCharsets.UTF_8);
        byte[] decrypted = CryptoUtil.decryptGcm(
                Base64.getDecoder().decode(payload.getCipherText()),
                spaSessionKey,
                Base64.getDecoder().decode(outHeaders.getFirst(GatewayConstants.X_ENCRYPT_IV)),
                aad);
        assertEquals(plainProfile, new String(decrypted, StandardCharsets.UTF_8));

        // 2. 无体 GET 缺失 X-Encrypt-Key 直接 Fail-Closed 返回 HTTP 400
        MockServerHttpRequest missingKeyReq = MockServerHttpRequest.get("/api/crypto/spa/profile").build();
        MockServerWebExchange missingKeyExchange = MockServerWebExchange.from(missingKeyReq);
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        filter.filter(missingKeyExchange, ex -> {
            downstreamCalled.set(true);
            return Mono.empty();
        }).block();

        assertFalse(downstreamCalled.get());
        assertEquals(HttpStatus.BAD_REQUEST, missingKeyExchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("PooledByteBufAllocator 下异常(400/413)与正常出站超限(413)均确定性释放堆外缓冲 refCnt==0")
    void shouldReleasePooledBuffersOn400And413AndUnsupportedAlgorithm() throws Exception {
        KeyPair serverKp = RsaUtil.generateKeyPair();
        KeyPair clientKp = RsaUtil.generateKeyPair();
        AtengGatewayCryptoProperties props = baseProperties(serverKp, clientKp);
        props.setMaxBodySize(DataSize.ofBytes(256));
        GatewayCryptoFilter filter = createFilter(props);

        NettyDataBufferFactory pooledFactory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);

        // Case 1: 篡改密文返回 400 且入站 PooledDataBuffer refCnt == 0
        byte[] badEnvelope = "{\"cipherText\":\"YWJj\"}".getBytes(StandardCharsets.UTF_8);
        NettyDataBuffer pooledBad = pooledFactory.allocateBuffer(badEnvelope.length);
        pooledBad.write(badEnvelope);

        GatewayCipherResult dummyEnc = rsaCipher.encrypt(
                "x".getBytes(StandardCharsets.UTF_8),
                RsaUtil.encodePublicKeyBase64(serverKp.getPublic()),
                null);
        MockServerHttpRequest badReq = MockServerHttpRequest.post("/api/crypto/order")
                .header(GatewayConstants.X_ENCRYPT_KEY, dummyEnc.encryptedKey())
                .header(GatewayConstants.X_ENCRYPT_IV, dummyEnc.iv())
                .body(Flux.just((DataBuffer) pooledBad));
        MockServerWebExchange badExchange = MockServerWebExchange.from(badReq);
        filter.filter(badExchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.BAD_REQUEST, badExchange.getResponse().getStatusCode());
        assertEquals(0, pooledBad.getNativeBuffer().refCnt());

        // Case 2: 非法 X-Encrypt-Alg 返回 400
        MockServerHttpRequest badAlgReq = MockServerHttpRequest.post("/api/crypto/order")
                .header(GatewayConstants.X_ENCRYPT_ALG, "DES_ECB")
                .body("{}");
        MockServerWebExchange badAlgExchange = MockServerWebExchange.from(badAlgReq);
        filter.filter(badAlgExchange, ex -> Mono.empty()).block();
        assertEquals(HttpStatus.BAD_REQUEST, badAlgExchange.getResponse().getStatusCode());

        // Case 3: 入站流未带 Content-Length 但实际超过 256B -> 返回 413 且 PooledDataBuffer refCnt == 0
        byte[] largeReqBytes = "A".repeat(400).getBytes(StandardCharsets.UTF_8);
        NettyDataBuffer pooledLarge = pooledFactory.allocateBuffer(largeReqBytes.length);
        pooledLarge.write(largeReqBytes);
        MockServerHttpRequest largeReq = MockServerHttpRequest.post("/api/crypto/order")
                .header(GatewayConstants.X_ENCRYPT_KEY, dummyEnc.encryptedKey())
                .header(GatewayConstants.X_ENCRYPT_IV, dummyEnc.iv())
                .body(Flux.just((DataBuffer) pooledLarge));
        MockServerWebExchange largeExchange = MockServerWebExchange.from(largeReq);
        filter.filter(largeExchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, largeExchange.getResponse().getStatusCode());
        assertEquals(0, pooledLarge.getNativeBuffer().refCnt());

        // Case 4: 出站响应超过 256B -> 返回 413 Result.fail 且响应缓冲 refCnt == 0
        GatewayCipherResult validReqEnc = rsaCipher.encrypt(
                "{\"id\":1}".getBytes(StandardCharsets.UTF_8),
                RsaUtil.encodePublicKeyBase64(serverKp.getPublic()),
                null);
        String smallEnvelope = objectMapper.writeValueAsString(
                new GatewayEncryptedPayload(Base64.getEncoder().encodeToString(validReqEnc.cipherBytes())));
        MockServerHttpRequest outOverflowReq = MockServerHttpRequest.post("/api/crypto/order")
                .header(GatewayConstants.X_ENCRYPT_KEY, validReqEnc.encryptedKey())
                .header(GatewayConstants.X_ENCRYPT_IV, validReqEnc.iv())
                .body(smallEnvelope);
        MockServerWebExchange outOverflowExchange = MockServerWebExchange.from(outOverflowReq);

        byte[] largeOutBytes = ("{\"big\":\"" + "X".repeat(400) + "\"}").getBytes(StandardCharsets.UTF_8);
        NettyDataBuffer pooledOut = pooledFactory.allocateBuffer(largeOutBytes.length);
        pooledOut.write(largeOutBytes);

        filter.filter(outOverflowExchange, ex -> ex.getResponse().writeWith(Flux.just((DataBuffer) pooledOut))).block();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, outOverflowExchange.getResponse().getStatusCode());
        assertEquals(0, pooledOut.getNativeBuffer().refCnt());
        JsonNode errNode = objectMapper.readTree(outOverflowExchange.getResponse().getBodyAsString().block());
        assertEquals(413, errNode.get("code").asInt());
    }

    @Test
    @DisplayName("非强制 required=false 兼容明文透传且放行 SSE/二进制流量")
    void shouldSupportOptionalPlaintextAndBypassSse() {
        KeyPair serverKp = RsaUtil.generateKeyPair();
        KeyPair clientKp = RsaUtil.generateKeyPair();
        AtengGatewayCryptoProperties props = baseProperties(serverKp, clientKp);
        props.setRequired(false);
        GatewayCryptoFilter filter = createFilter(props);

        // 明文请求无加密头，在 required=false 下透传明文且响应不加密
        String rawPlain = "{\"legacy\":true}";
        MockServerHttpRequest plainReq = MockServerHttpRequest.post("/api/crypto/legacy")
                .contentType(MediaType.APPLICATION_JSON)
                .body(rawPlain);
        MockServerWebExchange plainExchange = MockServerWebExchange.from(plainReq);
        filter.filter(plainExchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            return ex.getResponse().writeWith(Mono.just(
                    ex.getResponse().bufferFactory().wrap("{\"plainResp\":1}".getBytes(StandardCharsets.UTF_8))));
        }).block();

        assertEquals(HttpStatus.OK, plainExchange.getResponse().getStatusCode());
        assertEquals("{\"plainResp\":1}", plainExchange.getResponse().getBodyAsString().block());
        assertFalse(plainExchange.getResponse().getHeaders().containsKey(GatewayConstants.X_ENCRYPT_KEY));

        // SSE 响应不缓冲加密
        MockServerHttpRequest sseReq = MockServerHttpRequest.get("/api/crypto/stream/events")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .build();
        MockServerWebExchange sseExchange = MockServerWebExchange.from(sseReq);
        filter.filter(sseExchange, ex -> {
            ex.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            return ex.getResponse().writeAndFlushWith(Flux.just(Flux.just(
                    ex.getResponse().bufferFactory().wrap("data: hello\n\n".getBytes(StandardCharsets.UTF_8)))));
        }).block();

        assertEquals("data: hello\n\n", sseExchange.getResponse().getBodyAsString().block());
        assertFalse(sseExchange.getResponse().getHeaders().containsKey(GatewayConstants.X_ENCRYPT_KEY));
    }

    @Test
    @DisplayName("REQUEST_SESSION_KEY 有体 POST 全链路与 SSE Accept:text/event-stream 直通验证")
    void shouldSupportSpaPostWithBodyAndSseAcceptUnderSessionKeyMode() throws Exception {
        KeyPair serverKp = RsaUtil.generateKeyPair();
        AtengGatewayCryptoProperties props = baseProperties(serverKp, null);
        props.setResponseKeyMode(GatewayCryptoResponseKeyMode.REQUEST_SESSION_KEY);
        props.setBindAad(true);
        GatewayCryptoFilter filter = createFilter(props);

        // 1. 有体 POST 解密 + 复用同一会话密钥配合新随机 IV 加密响应
        byte[] sessionKey = CryptoUtil.generateKey(256);
        byte[] reqIv = CryptoUtil.generateGcmIv();
        byte[] aad = "default|v1|RSA_AES_GCM".getBytes(StandardCharsets.UTF_8);
        String plainReq = "{\"op\":\"transfer\",\"amount\":100}";
        byte[] cipherReq = CryptoUtil.encryptGcm(plainReq.getBytes(StandardCharsets.UTF_8), sessionKey, reqIv, aad);
        String reqIvB64 = Base64.getEncoder().encodeToString(reqIv);
        String wrappedKey = Base64.getEncoder().encodeToString(
                RsaUtil.encryptOaep(sessionKey, serverKp.getPublic()));
        String reqEnvelope = objectMapper.writeValueAsString(
                new GatewayEncryptedPayload(Base64.getEncoder().encodeToString(cipherReq)));

        MockServerHttpRequest postReq = MockServerHttpRequest.post("/api/crypto/transfer")
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayConstants.X_ENCRYPT_KEY, wrappedKey)
                .header(GatewayConstants.X_ENCRYPT_IV, reqIvB64)
                .body(reqEnvelope);
        MockServerWebExchange postExchange = MockServerWebExchange.from(postReq);

        String plainResp = "{\"ok\":true}";
        filter.filter(postExchange, ex -> DataBufferUtils.join(ex.getRequest().getBody())
                .flatMap(buf -> {
                    byte[] b = new byte[buf.readableByteCount()];
                    buf.read(b);
                    DataBufferUtils.release(buf);
                    assertEquals(plainReq, new String(b, StandardCharsets.UTF_8));
                    ex.getResponse().setStatusCode(HttpStatus.OK);
                    return ex.getResponse().writeWith(Mono.just(
                            ex.getResponse().bufferFactory().wrap(plainResp.getBytes(StandardCharsets.UTF_8))));
                })).block();

        HttpHeaders respHeaders = postExchange.getResponse().getHeaders();
        assertFalse(respHeaders.containsKey(GatewayConstants.X_ENCRYPT_KEY));
        String respIvB64 = respHeaders.getFirst(GatewayConstants.X_ENCRYPT_IV);
        assertNotNull(respIvB64);
        assertFalse(reqIvB64.equals(respIvB64));
        GatewayEncryptedPayload outPayload = objectMapper.readValue(
                postExchange.getResponse().getBodyAsString().block(), GatewayEncryptedPayload.class);
        byte[] decryptedResp = CryptoUtil.decryptGcm(
                Base64.getDecoder().decode(outPayload.getCipherText()),
                sessionKey,
                Base64.getDecoder().decode(respIvB64),
                aad);
        assertEquals(plainResp, new String(decryptedResp, StandardCharsets.UTF_8));

        // 2. REQUEST_SESSION_KEY + required=true 下 GET 仅带 Accept: text/event-stream 直通跳过握手 400
        MockServerHttpRequest sseGet = MockServerHttpRequest.get("/api/crypto/stream/events")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .build();
        MockServerWebExchange sseExchange = MockServerWebExchange.from(sseGet);
        filter.filter(sseExchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            ex.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            return ex.getResponse().writeAndFlushWith(Flux.just(Flux.just(
                    ex.getResponse().bufferFactory().wrap("data: spa-sse\n\n".getBytes(StandardCharsets.UTF_8)))));
        }).block();

        assertEquals(HttpStatus.OK, sseExchange.getResponse().getStatusCode());
        assertEquals("data: spa-sse\n\n", sseExchange.getResponse().getBodyAsString().block());
    }

    @Test
    @DisplayName("方向开关、细分路径、自定义标头、strip=false、附件放行、204/HEAD/错误加密及异常透传闭环")
    void shouldSupportFineGrainedDirectionPathsCustomHeadersAndExceptionPropagation() throws Exception {
        KeyPair serverKp = RsaUtil.generateKeyPair();
        KeyPair clientKp = RsaUtil.generateKeyPair();
        AtengGatewayCryptoProperties props = baseProperties(serverKp, clientKp);
        props.setIncludePaths(List.of());
        props.setExcludePaths(List.of("/api/crypto/skip/**"));
        props.setDecryptIncludePaths(List.of("/api/crypto/in-only"));
        props.setEncryptIncludePaths(List.of("/api/crypto/out-only", "/api/crypto/err-enc", "/api/crypto/empty-ok", "/api/crypto/no-content", "/api/crypto/head-ok", "/api/crypto/attachment"));
        props.setStripCryptoHeaders(false);
        props.setEncryptError(true);
        props.setHeaderEncryptKey("X-Custom-Key");
        props.setHeaderEncryptIv("X-Custom-Iv");
        props.setHeaderKeyId("X-Custom-Kid");
        props.setHeaderEncryptAlg("X-Custom-Alg");
        GatewayCryptoFilter filter = createFilter(props);

        // 1. strip=false + 自定义标头名 + 仅解密路径（出站不加密）
        String plainReq = "{\"onlyIn\":1}";
        GatewayCipherResult reqEnc = rsaCipher.encrypt(
                plainReq.getBytes(StandardCharsets.UTF_8),
                RsaUtil.encodePublicKeyBase64(serverKp.getPublic()),
                null);
        String encBody = objectMapper.writeValueAsString(
                new GatewayEncryptedPayload(Base64.getEncoder().encodeToString(reqEnc.cipherBytes())));

        MockServerHttpRequest inOnlyReq = MockServerHttpRequest.post("/api/crypto/in-only")
                .header("X-Custom-Key", reqEnc.encryptedKey())
                .header("X-Custom-Iv", reqEnc.iv())
                .header("X-Custom-Kid", "v1")
                .body(encBody);
        MockServerWebExchange inOnlyExchange = MockServerWebExchange.from(inOnlyReq);
        AtomicBoolean keyVisibleDownstream = new AtomicBoolean(false);
        filter.filter(inOnlyExchange, ex -> {
            keyVisibleDownstream.set(ex.getRequest().getHeaders().containsKey("X-Custom-Key"));
            ex.getResponse().setStatusCode(HttpStatus.OK);
            return ex.getResponse().writeWith(Mono.just(
                    ex.getResponse().bufferFactory().wrap("{\"rawOut\":true}".getBytes(StandardCharsets.UTF_8))));
        }).block();
        assertTrue(keyVisibleDownstream.get());
        assertEquals("{\"rawOut\":true}", inOnlyExchange.getResponse().getBodyAsString().block());
        assertFalse(inOnlyExchange.getResponse().getHeaders().containsKey("X-Custom-Key"));

        // 2. encryptError=true 对 400 响应执行加密
        MockServerWebExchange errEncEx = MockServerWebExchange.from(MockServerHttpRequest.get("/api/crypto/err-enc").build());
        filter.filter(errEncEx, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return ex.getResponse().writeWith(Mono.just(
                    ex.getResponse().bufferFactory().wrap("{\"code\":400,\"msg\":\"err\"}".getBytes(StandardCharsets.UTF_8))));
        }).block();
        assertTrue(errEncEx.getResponse().getHeaders().containsKey("X-Custom-Key"));

        // 3. setComplete() 200 OK 空体触发加密；204 No Content 与 HEAD 均跳过加密
        MockServerWebExchange empty200 = MockServerWebExchange.from(MockServerHttpRequest.get("/api/crypto/empty-ok").build());
        filter.filter(empty200, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            return ex.getResponse().setComplete();
        }).block();
        assertTrue(empty200.getResponse().getHeaders().containsKey("X-Custom-Key"));

        MockServerWebExchange noContent204 = MockServerWebExchange.from(MockServerHttpRequest.get("/api/crypto/no-content").build());
        filter.filter(noContent204, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return ex.getResponse().setComplete();
        }).block();
        assertFalse(noContent204.getResponse().getHeaders().containsKey("X-Custom-Key"));

        MockServerWebExchange headOk = MockServerWebExchange.from(MockServerHttpRequest.head("/api/crypto/head-ok").build());
        filter.filter(headOk, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            return ex.getResponse().setComplete();
        }).block();
        assertFalse(headOk.getResponse().getHeaders().containsKey("X-Custom-Key"));

        // 4. Content-Disposition: attachment 自动跳过出站加密
        MockServerWebExchange attachEx = MockServerWebExchange.from(MockServerHttpRequest.get("/api/crypto/attachment").build());
        filter.filter(attachEx, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            ex.getResponse().getHeaders().set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=demo.bin");
            return ex.getResponse().writeWith(Mono.just(
                    ex.getResponse().bufferFactory().wrap("binary-bytes".getBytes(StandardCharsets.UTF_8))));
        }).block();
        assertEquals("binary-bytes", attachEx.getResponse().getBodyAsString().block());
        assertFalse(attachEx.getResponse().getHeaders().containsKey("X-Custom-Key"));

        // 5. multipart/form-data 入站跳过解密 & excludePaths 放行
        MockServerWebExchange multipartEx = MockServerWebExchange.from(MockServerHttpRequest.post("/api/crypto/in-only")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body("--boundary\r\n"));
        filter.filter(multipartEx, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            return ex.getResponse().setComplete();
        }).block();
        assertEquals(HttpStatus.OK, multipartEx.getResponse().getStatusCode());

        // 6. 下游 BusinessException(1001) 原样向外传播不被 Filter 吞并转为 500
        io.github.atengk.boot.core.exception.BusinessException propagated = org.junit.jupiter.api.Assertions.assertThrows(
                io.github.atengk.boot.core.exception.BusinessException.class,
                () -> filter.filter(
                        MockServerWebExchange.from(MockServerHttpRequest.get("/api/crypto/empty-ok").build()),
                        ex -> Mono.error(new io.github.atengk.boot.core.exception.BusinessException(1001, "downstream-biz-err"))
                ).block());
        assertEquals(1001, propagated.getCode());

        // 7. 入站 Content-Length 预检超限 413 + 出站缺少客户端公钥 500 清理加密头
        props.setMaxBodySize(DataSize.ofBytes(128));
        MockServerWebExchange precheck413 = MockServerWebExchange.from(MockServerHttpRequest.post("/api/crypto/in-only")
                .header(HttpHeaders.CONTENT_LENGTH, "1024")
                .body("small"));
        filter.filter(precheck413, ex -> Mono.empty()).block();
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, precheck413.getResponse().getStatusCode());

        props.setRsaClientPublicKey(null);
        MockServerWebExchange outMissingPub500 = MockServerWebExchange.from(MockServerHttpRequest.get("/api/crypto/out-only").build());
        filter.filter(outMissingPub500, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            return ex.getResponse().writeWith(Mono.just(
                    ex.getResponse().bufferFactory().wrap("{\"x\":1}".getBytes(StandardCharsets.UTF_8))));
        }).block();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, outMissingPub500.getResponse().getStatusCode());
        assertFalse(outMissingPub500.getResponse().getHeaders().containsKey("X-Custom-Key"));
        assertFalse(outMissingPub500.getResponse().getHeaders().containsKey("X-Custom-Iv"));
    }
}
