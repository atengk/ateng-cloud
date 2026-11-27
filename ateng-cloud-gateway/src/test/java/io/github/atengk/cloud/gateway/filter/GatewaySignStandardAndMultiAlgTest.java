package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.boot.core.util.RsaUtil;
import io.github.atengk.boot.core.util.Sm2Util;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties.SignAppProperties;
import io.github.atengk.cloud.gateway.provider.InMemoryReactiveGatewayNonceValidator;
import io.github.atengk.cloud.gateway.provider.PropertiesGatewaySignKeyProvider;
import io.github.atengk.cloud.gateway.sign.enums.GatewaySignAlgorithm;
import io.github.atengk.cloud.gateway.sign.enums.GatewaySignCanonicalMode;
import io.github.atengk.cloud.gateway.sign.signer.HmacSha256GatewaySigner;
import io.github.atengk.cloud.gateway.sign.signer.RsaGatewaySigner;
import io.github.atengk.cloud.gateway.sign.signer.Sm2GatewaySigner;
import io.github.atengk.cloud.gateway.sign.util.GatewaySignCanonicalUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一规范签协议（Method/URI/Query/Body）、多算法（HMAC/RSA/SM2）与出站响应加签对抗单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
@DisplayName("网关统一规范签协议、多算法与响应加签测试")
class GatewaySignStandardAndMultiAlgTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("STANDARD 模式下修改 Method、URI、Query 顺序或 Body 任一字节均验签失败")
    void testStandardCanonicalTamperSensitivity() throws Exception {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setCanonicalMode(GatewaySignCanonicalMode.STANDARD);
        props.setAlgorithm(GatewaySignAlgorithm.HMAC_SHA256);
        props.setSecretKey("std-secret-key-001");

        GatewaySignGuardFilter filter = createFilter(props);

        String ts = String.valueOf(System.currentTimeMillis());
        String body = "{\"amount\":100,\"currency\":\"CNY\"}";
        String bodyHash = DigestUtil.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
        // 明确按独立 6 行规范串协议手工拼接预期值（Query 键值双排序：a=1&b=2&b=9）
        String canonical = "POST\n/api/open/orders\n" + ts + "\nn-std-1\na=1&b=2&b=9\n" + bodyHash;
        String sig = hmacBase64(canonical, "std-secret-key-001");

        // 1. 合法请求（故意乱序传 query：b=9&a=1&b=2）应放行
        MockServerHttpRequest validReq = MockServerHttpRequest
                .post("/api/open/orders?b=9&a=1&b=2")
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayConstants.X_APP_ID, "open-client")
                .header(GatewayConstants.X_TIMESTAMP, ts)
                .header(GatewayConstants.X_NONCE, "n-std-1")
                .header(GatewayConstants.X_SIGNATURE, sig)
                .body(body);
        MockServerWebExchange validEx = MockServerWebExchange.from(validReq);
        AtomicBoolean passed = new AtomicBoolean(false);
        filter.filter(validEx, ex -> {
            passed.set(true);
            return Mono.empty();
        }).block();
        assertTrue(passed.get(), "规范串一致（含无序多值 Query 规范化后）应放行");

        // 2. 修改 HTTP Method 为 PUT（复用同一签名与新 Nonce，先按 POST 签名再发 PUT）
        String canonicalPost = "POST\n/api/open/orders\n" + ts + "\nn-std-2\na=1&b=2&b=9\n" + bodyHash;
        String sigPost = hmacBase64(canonicalPost, "std-secret-key-001");
        MockServerHttpRequest diffMethodReq = MockServerHttpRequest
                .put("/api/open/orders?b=9&a=1&b=2")
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayConstants.X_APP_ID, "open-client")
                .header(GatewayConstants.X_TIMESTAMP, ts)
                .header(GatewayConstants.X_NONCE, "n-std-2")
                .header(GatewayConstants.X_SIGNATURE, sigPost)
                .body(body);
        MockServerWebExchange diffMethodEx = MockServerWebExchange.from(diffMethodReq);
        filter.filter(diffMethodEx, ex -> Mono.empty()).block();
        assertEquals(HttpStatus.UNAUTHORIZED, diffMethodEx.getResponse().getStatusCode(), "修改 Method 必须被拒绝");

        // 3. 修改 URI 路径为 /api/open/orders/v2
        String canonicalUri = "POST\n/api/open/orders\n" + ts + "\nn-std-3\na=1&b=2&b=9\n" + bodyHash;
        String sigUri = hmacBase64(canonicalUri, "std-secret-key-001");
        MockServerHttpRequest diffUriReq = MockServerHttpRequest
                .post("/api/open/orders/v2?b=9&a=1&b=2")
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayConstants.X_APP_ID, "open-client")
                .header(GatewayConstants.X_TIMESTAMP, ts)
                .header(GatewayConstants.X_NONCE, "n-std-3")
                .header(GatewayConstants.X_SIGNATURE, sigUri)
                .body(body);
        MockServerWebExchange diffUriEx = MockServerWebExchange.from(diffUriReq);
        filter.filter(diffUriEx, ex -> Mono.empty()).block();
        assertEquals(HttpStatus.UNAUTHORIZED, diffUriEx.getResponse().getStatusCode(), "修改 URI 必须被拒绝");

        // 4. 修改 Query 参数值
        String canonicalQuery = "POST\n/api/open/orders\n" + ts + "\nn-std-4\na=1&b=2&b=9\n" + bodyHash;
        String sigQuery = hmacBase64(canonicalQuery, "std-secret-key-001");
        MockServerHttpRequest diffQueryReq = MockServerHttpRequest
                .post("/api/open/orders?b=9&a=1&b=3")
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayConstants.X_APP_ID, "open-client")
                .header(GatewayConstants.X_TIMESTAMP, ts)
                .header(GatewayConstants.X_NONCE, "n-std-4")
                .header(GatewayConstants.X_SIGNATURE, sigQuery)
                .body(body);
        MockServerWebExchange diffQueryEx = MockServerWebExchange.from(diffQueryReq);
        filter.filter(diffQueryEx, ex -> Mono.empty()).block();
        assertEquals(HttpStatus.UNAUTHORIZED, diffQueryEx.getResponse().getStatusCode(), "篡改 Query 值必须被拒绝");

        // 5. 篡改 Body 任一字节
        String canonicalBody = "POST\n/api/open/orders\n" + ts + "\nn-std-5\na=1&b=2&b=9\n" + bodyHash;
        String sigBody = hmacBase64(canonicalBody, "std-secret-key-001");
        MockServerHttpRequest diffBodyReq = MockServerHttpRequest
                .post("/api/open/orders?b=9&a=1&b=2")
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayConstants.X_APP_ID, "open-client")
                .header(GatewayConstants.X_TIMESTAMP, ts)
                .header(GatewayConstants.X_NONCE, "n-std-5")
                .header(GatewayConstants.X_SIGNATURE, sigBody)
                .body("{\"amount\":101,\"currency\":\"CNY\"}");
        MockServerWebExchange diffBodyEx = MockServerWebExchange.from(diffBodyReq);
        filter.filter(diffBodyEx, ex -> Mono.empty()).block();
        assertEquals(HttpStatus.UNAUTHORIZED, diffBodyEx.getResponse().getStatusCode(), "篡改 Body 任一字节必须被拒绝");
    }

    @Test
    @DisplayName("RSA 非对称双向加验签（入站客户端私钥加签、出站网关私钥回签）")
    void testRsaVerifyAndOutboundResponseSign() {
        KeyPair clientKp = RsaUtil.generateKeyPair(2048);
        KeyPair serverKp = RsaUtil.generateKeyPair(2048);

        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setCanonicalMode(GatewaySignCanonicalMode.STANDARD);
        props.setAlgorithm(GatewaySignAlgorithm.RSA);
        props.setSignResponse(true);
        SignAppProperties appProps = new SignAppProperties();
        appProps.setRsaPublicKey(RsaUtil.encodePublicKeyBase64(clientKp.getPublic()));
        appProps.setRsaPrivateKey(RsaUtil.encodePrivateKeyBase64(serverKp.getPrivate()));
        props.getApps().put("rsa-partner", appProps);

        GatewaySignGuardFilter filter = createFilter(props);

        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = "rsa-nonce-1";
        String body = "{\"item\":\"book\"}";
        String bodyHash = DigestUtil.sha256Hex(body);
        String reqCanonical = "POST\n/api/open/rsa/order\n" + ts + "\n" + nonce + "\n\n" + bodyHash;
        String reqSig = RsaUtil.signSha256Base64(reqCanonical, RsaUtil.encodePrivateKeyBase64(clientKp.getPrivate()));

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/open/rsa/order")
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayConstants.X_APP_ID, "rsa-partner")
                .header(GatewayConstants.X_TIMESTAMP, ts)
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, reqSig)
                .body(body);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String respJson = "{\"code\":200,\"data\":{\"status\":\"OK\"}}";
        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            ex.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] respBytes = respJson.getBytes(StandardCharsets.UTF_8);
            return ex.getResponse().writeWith(Mono.just(ex.getResponse().bufferFactory().wrap(respBytes)));
        }).block();

        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        String respSig = exchange.getResponse().getHeaders().getFirst(GatewayConstants.X_SIGNATURE);
        String respTs = exchange.getResponse().getHeaders().getFirst(GatewayConstants.X_TIMESTAMP);
        String respNonce = exchange.getResponse().getHeaders().getFirst(GatewayConstants.X_NONCE);
        assertNotNull(respSig, "开启 signResponse 时必须回写 X-Signature");
        assertEquals(ts, respTs);
        assertEquals(nonce, respNonce);

        String actualRespBody = exchange.getResponse().getBodyAsString().block();
        assertEquals(respJson, actualRespBody);
        String expectedOutCanonical = GatewaySignCanonicalUtil.buildResponseCanonicalString(
                respTs, respNonce, DigestUtil.sha256Hex(actualRespBody));
        assertTrue(RsaUtil.verifySha256Base64(
                expectedOutCanonical, respSig, RsaUtil.encodePublicKeyBase64(serverKp.getPublic())),
                "客户端使用服务端公钥校验出站 RSA 签名必须通过");
    }

    @Test
    @DisplayName("国密 SM2 非对称双向加验签（含无体 GET 6 行规范串空摘要）")
    void testSm2VerifyAndOutboundResponseSign() {
        Sm2Util.Sm2KeyPair clientKp = Sm2Util.generateKeyPair();
        Sm2Util.Sm2KeyPair serverKp = Sm2Util.generateKeyPair();

        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setCanonicalMode(GatewaySignCanonicalMode.STANDARD);
        props.setAlgorithm(GatewaySignAlgorithm.SM2);
        props.setSignResponse(true);
        props.setAppId("default-sm2-app");
        props.setSm2PublicKey(clientKp.publicKeyBase64());
        props.setSm2PrivateKey(serverKp.privateKeyBase64());

        GatewaySignGuardFilter filter = createFilter(props);

        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = "sm2-nonce-1";
        // 验证显式配置默认 appId 后允许不传 X-App-Id 头，且 GET 空体第 6 行为 sha256Hex("")
        String reqCanonical = "GET\n/api/open/sm2/info\n" + ts + "\n" + nonce + "\nid=42\n" + DigestUtil.sha256Hex("");
        String reqSig = Sm2Util.signBase64(reqCanonical, clientKp.privateKeyHex());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/open/sm2/info?id=42")
                .header(GatewayConstants.X_TIMESTAMP, ts)
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, reqSig)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String respJson = "{\"code\":200,\"msg\":\"sm2-ok\"}";
        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            ex.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return ex.getResponse().writeWith(
                    Mono.just(ex.getResponse().bufferFactory().wrap(respJson.getBytes(StandardCharsets.UTF_8))));
        }).block();

        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        String respSig = exchange.getResponse().getHeaders().getFirst(GatewayConstants.X_SIGNATURE);
        assertNotNull(respSig);
        String respCanonical = ts + "\n" + nonce + "\n" + DigestUtil.sha256Hex(respJson);
        assertTrue(Sm2Util.verifyBase64(respCanonical, respSig, serverKp.publicKeyHex()),
                "客户端使用服务端 SM2 公钥校验出站响应签名必须通过");
    }

    @Test
    @DisplayName("开启 signResponse 时默认跳过错误响应、SSE 流式与二进制下载，超限响应体释放缓冲并返 413")
    void testOutboundSignBoundaryErrorStreamingOversizeAndMissingKey() throws Exception {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setCanonicalMode(GatewaySignCanonicalMode.STANDARD);
        props.setAlgorithm(GatewaySignAlgorithm.HMAC_SHA256);
        props.setSignResponse(true);
        props.setMaxBodySize(org.springframework.util.unit.DataSize.ofBytes(32));
        props.setSecretKey("boundary-secret");

        GatewaySignGuardFilter filter = createFilter(props);

        // 1. 下游返回 400 错误且 signErrorResponse=false -> 不写入签名头
        String ts1 = String.valueOf(System.currentTimeMillis());
        MockServerWebExchange errEx = createSignedGetExchange("/api/open/err", "app-1", ts1, "nb-1", "boundary-secret");
        filter.filter(errEx, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return ex.getResponse().writeWith(Mono.just(ex.getResponse().bufferFactory().wrap("err".getBytes(StandardCharsets.UTF_8))));
        }).block();
        assertNull(errEx.getResponse().getHeaders().getFirst(GatewayConstants.X_SIGNATURE),
                "错误响应默认不得回写签名头");

        // 2. 下游返回 text/event-stream -> 零缓冲直通且即使总字节超过 32B 也不报错
        String ts2 = String.valueOf(System.currentTimeMillis());
        MockServerWebExchange sseEx = createSignedGetExchange("/api/open/sse", "app-1", ts2, "nb-2", "boundary-secret");
        filter.filter(sseEx, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            ex.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            return ex.getResponse().writeAndFlushWith(Flux.just(Flux.just(
                    ex.getResponse().bufferFactory().wrap("data: first-event-exceeding-limit\n\n".getBytes(StandardCharsets.UTF_8)),
                    ex.getResponse().bufferFactory().wrap("data: second-event-exceeding-limit\n\n".getBytes(StandardCharsets.UTF_8))
            )));
        }).block();
        assertEquals(HttpStatus.OK, sseEx.getResponse().getStatusCode());
        assertNull(sseEx.getResponse().getHeaders().getFirst(GatewayConstants.X_SIGNATURE),
                "SSE 流式响应必须零缓冲直通且跳过出站加签");

        // 3. 响应体超过 maxBodySize (32B) -> 释放池化缓冲并返 413
        String ts3 = String.valueOf(System.currentTimeMillis());
        MockServerWebExchange overEx = createSignedGetExchange("/api/open/large", "app-1", ts3, "nb-3", "boundary-secret");
        NettyDataBufferFactory pooledFactory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        ByteBuf nativeBuf = PooledByteBufAllocator.DEFAULT.buffer();
        nativeBuf.writeBytes("0123456789012345678901234567890123456789".getBytes(StandardCharsets.UTF_8));
        NettyDataBuffer pooledBuffer = pooledFactory.wrap(nativeBuf);
        try {
            filter.filter(overEx, ex -> {
                ex.getResponse().setStatusCode(HttpStatus.OK);
                ex.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return ex.getResponse().writeWith(Mono.just(pooledBuffer));
            }).block();

            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, overEx.getResponse().getStatusCode());
            assertNull(overEx.getResponse().getHeaders().getFirst(GatewayConstants.X_SIGNATURE));
            assertEquals(0, pooledBuffer.getNativeBuffer().refCnt(), "超限响应缓冲必须确定性释放");
            JsonNode errJson = objectMapper.readTree(overEx.getResponse().getBodyAsString().block());
            assertEquals(413, errJson.get("code").asInt());
        } finally {
            if (pooledBuffer.getNativeBuffer().refCnt() > 0) {
                pooledBuffer.getNativeBuffer().release(pooledBuffer.getNativeBuffer().refCnt());
            }
        }

        // 4. RSA 验签通过但缺少出站私钥 -> Fail-Closed 500 且绝不写签名头
        KeyPair clientKp = RsaUtil.generateKeyPair(2048);
        AtengGatewaySignProperties rsaNoPrivProps = new AtengGatewaySignProperties();
        rsaNoPrivProps.setEnabled(true);
        rsaNoPrivProps.setCanonicalMode(GatewaySignCanonicalMode.STANDARD);
        rsaNoPrivProps.setAlgorithm(GatewaySignAlgorithm.RSA);
        rsaNoPrivProps.setSignResponse(true);
        rsaNoPrivProps.setRsaPublicKey(RsaUtil.encodePublicKeyBase64(clientKp.getPublic()));
        GatewaySignGuardFilter rsaFilter = createFilter(rsaNoPrivProps);

        String ts4 = String.valueOf(System.currentTimeMillis());
        String rsaReqCanonical = "GET\n/api/open/rsa-no-priv\n" + ts4 + "\nnb-4\n\n" + DigestUtil.sha256Hex("");
        String rsaReqSig = RsaUtil.signSha256Base64(rsaReqCanonical, RsaUtil.encodePrivateKeyBase64(clientKp.getPrivate()));
        MockServerWebExchange rsaNoPrivEx = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/open/rsa-no-priv")
                        .header(GatewayConstants.X_APP_ID, "app-rsa")
                        .header(GatewayConstants.X_TIMESTAMP, ts4)
                        .header(GatewayConstants.X_NONCE, "nb-4")
                        .header(GatewayConstants.X_SIGNATURE, rsaReqSig)
                        .build()
        );
        rsaFilter.filter(rsaNoPrivEx, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            return ex.getResponse().writeWith(Mono.just(ex.getResponse().bufferFactory().wrap("ok".getBytes(StandardCharsets.UTF_8))));
        }).block();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, rsaNoPrivEx.getResponse().getStatusCode());
        assertNull(rsaNoPrivEx.getResponse().getHeaders().getFirst(GatewayConstants.X_SIGNATURE));
    }

    @Test
    @DisplayName("HMAC_SHA256 + STANDARD 模式下开启 signResponse=true 回写 200 响应签名并校验通过，且 413 清除下游残留帧头")
    void testHmacStandardVerifyAndOutboundResponseSignAndFrameHeaderCleanup() {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setCanonicalMode(GatewaySignCanonicalMode.STANDARD);
        props.setAlgorithm(GatewaySignAlgorithm.HMAC_SHA256);
        props.setSignResponse(true);
        props.setMaxBodySize(org.springframework.util.unit.DataSize.ofBytes(64));
        props.setSecretKey("hmac-std-out-secret");

        GatewaySignGuardFilter filter = createFilter(props);

        // 1. HMAC_SHA256 正常 200 出站响应加签回写并校验
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = "hmac-out-1";
        String body = "{\"item\":\"tea\"}";
        String reqCanonical = "POST\n/api/open/hmac/order\n" + ts + "\n" + nonce + "\n\n" + DigestUtil.sha256Hex(body);
        String reqSig = HmacUtil.hmacSha256Base64(reqCanonical, "hmac-std-out-secret");

        MockServerWebExchange okEx = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/open/hmac/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(GatewayConstants.X_APP_ID, "hmac-app")
                        .header(GatewayConstants.X_TIMESTAMP, ts)
                        .header(GatewayConstants.X_NONCE, nonce)
                        .header(GatewayConstants.X_SIGNATURE, reqSig)
                        .body(body)
        );
        String respJson = "{\"code\":200,\"data\":\"hmac-ok\"}";
        filter.filter(okEx, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            ex.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return ex.getResponse().writeWith(Mono.just(
                    ex.getResponse().bufferFactory().wrap(respJson.getBytes(StandardCharsets.UTF_8))));
        }).block();

        assertEquals(HttpStatus.OK, okEx.getResponse().getStatusCode());
        String respSig = okEx.getResponse().getHeaders().getFirst(GatewayConstants.X_SIGNATURE);
        assertNotNull(respSig);
        String respCanonical = GatewaySignCanonicalUtil.buildResponseCanonicalString(
                ts, nonce, DigestUtil.sha256Hex(respJson));
        assertTrue(HmacUtil.verifyBase64(
                HmacUtil.hmacSha256Base64(respCanonical, "hmac-std-out-secret"), respSig));

        // 2. 下游预置 Transfer-Encoding / Content-Length 且超限时，413 响应必须清理残留帧头并精确对齐 Content-Length
        String ts2 = String.valueOf(System.currentTimeMillis());
        MockServerWebExchange overEx = createSignedGetExchange("/api/open/hmac/over", "hmac-app", ts2, "hmac-out-2", "hmac-std-out-secret");
        filter.filter(overEx, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            ex.getResponse().getHeaders().set(HttpHeaders.TRANSFER_ENCODING, "chunked");
            ex.getResponse().getHeaders().setContentLength(9999);
            byte[] large = "0123456789012345678901234567890123456789012345678901234567890123456789".getBytes(StandardCharsets.UTF_8);
            return ex.getResponse().writeWith(Mono.just(ex.getResponse().bufferFactory().wrap(large)));
        }).block();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, overEx.getResponse().getStatusCode());
        assertNull(overEx.getResponse().getHeaders().getFirst(HttpHeaders.TRANSFER_ENCODING),
                "413 错包写回前必须清除下游预置 Transfer-Encoding");
        byte[] errBytes = overEx.getResponse().getBodyAsString().block().getBytes(StandardCharsets.UTF_8);
        assertEquals(errBytes.length, overEx.getResponse().getHeaders().getContentLength(),
                "413 响应 Content-Length 必须精确等于错误 JSON 长度");
    }

    @Test
    @DisplayName("PropertiesGatewaySignKeyProvider 对空或空白 appId 快速返回空 Mono 不回退顶层密钥")
    void testPropertiesKeyProviderRejectsBlankAppId() {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setSecretKey("top-level-secret");
        props.setPublicKey("top-level-public");
        props.setPrivateKey("top-level-private");

        PropertiesGatewaySignKeyProvider provider = new PropertiesGatewaySignKeyProvider(props);
        assertNull(provider.getSecretKey(null).block());
        assertNull(provider.getSecretKey("   ").block());
        assertNull(provider.getPublicKey("").block());
        assertNull(provider.getPrivateKey(null).block());
        assertNull(provider.getVerifyKey(" ", GatewaySignAlgorithm.RSA).block());
        assertNull(provider.getSignKey(null, GatewaySignAlgorithm.SM2).block());
        assertEquals("top-level-secret", provider.getSecretKey("valid-app").block());
    }

    private MockServerWebExchange createSignedGetExchange(String uri,
                                                        String appId,
                                                        String ts,
                                                        String nonce,
                                                        String secret) {
        String canonical = "GET\n" + uri + "\n" + ts + "\n" + nonce + "\n\n" + DigestUtil.sha256Hex("");
        String sig = hmacBase64(canonical, secret);
        return MockServerWebExchange.from(MockServerHttpRequest.get(uri)
                .header(GatewayConstants.X_APP_ID, appId)
                .header(GatewayConstants.X_TIMESTAMP, ts)
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, sig)
                .build());
    }

    private GatewaySignGuardFilter createFilter(AtengGatewaySignProperties props) {
        return new GatewaySignGuardFilter(
                props,
                new PropertiesGatewaySignKeyProvider(props),
                new InMemoryReactiveGatewayNonceValidator(props.getNonceMaxCapacity()),
                List.of(new HmacSha256GatewaySigner(), new RsaGatewaySigner(), new Sm2GatewaySigner()),
                objectMapper
        );
    }

    private String hmacBase64(String data, String secret) {
        return HmacUtil.hmacSha256Base64(data, secret);
    }
}
