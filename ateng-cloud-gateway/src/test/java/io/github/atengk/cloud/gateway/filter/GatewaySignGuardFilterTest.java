package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import io.github.atengk.cloud.gateway.provider.InMemorySignSecretProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开放接口 API 签名防篡改与 Nonce 防重放过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewaySignGuardFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String calculateHmac(String canonicalString, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonicalString.getBytes(StandardCharsets.UTF_8)));
    }

    private String calculateSha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }

    @Test
    @DisplayName("测试合法签名请求顺利通过校验")
    void testValidSignaturePasses() throws Exception {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setTimestampThreshold(Duration.ofMinutes(5));

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        provider.registerSecret("app-test-01", "my-secret-key-12345");

        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        long now = System.currentTimeMillis();
        String nonce = "nonce-unique-abc-001";
        String canonical = "appId=app-test-01&nonce=" + nonce + "&timestamp=" + now + "&user=ateng";
        String signature = calculateHmac(canonical, "my-secret-key-12345");

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/open/data?user=ateng")
                .header(GatewayConstants.X_APP_ID, "app-test-01")
                .header(GatewayConstants.X_TIMESTAMP, String.valueOf(now))
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            executed.set(true);
            return Mono.empty();
        }).block();

        assertTrue(executed.get(), "签名合法应放行请求");
    }

    @Test
    @DisplayName("测试缺少核心标头时快速失败拦截")
    void testMissingHeadersBlocksWith401() {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/open/data").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            executed.set(true);
            return Mono.empty();
        }).block();

        assertFalse(executed.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("测试时间戳偏差超限（过期请求）被拦截")
    void testExpiredTimestampBlocksWith401() throws Exception {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setTimestampThreshold(Duration.ofSeconds(60));

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        provider.registerSecret("app-01", "secret");

        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        // 10 分钟前的时间戳
        long expiredTime = System.currentTimeMillis() - 600_000L;
        String canonical = "appId=app-01&nonce=n-1&timestamp=" + expiredTime;
        String signature = calculateHmac(canonical, "secret");

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/open/data")
                .header(GatewayConstants.X_APP_ID, "app-01")
                .header(GatewayConstants.X_TIMESTAMP, String.valueOf(expiredTime))
                .header(GatewayConstants.X_NONCE, "n-1")
                .header(GatewayConstants.X_SIGNATURE, signature)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("测试重复 Nonce 请求（重放攻击）被拦截")
    void testDuplicateNonceBlocksWith401() throws Exception {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        provider.registerSecret("app-01", "secret");

        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        long now = System.currentTimeMillis();
        String nonce = "replay-nonce-001";
        String canonical = "appId=app-01&nonce=" + nonce + "&timestamp=" + now;
        String signature = calculateHmac(canonical, "secret");

        MockServerHttpRequest req1 = MockServerHttpRequest.get("/api/open/data")
                .header(GatewayConstants.X_APP_ID, "app-01")
                .header(GatewayConstants.X_TIMESTAMP, String.valueOf(now))
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .build();
        MockServerWebExchange ex1 = MockServerWebExchange.from(req1);

        AtomicBoolean pass1 = new AtomicBoolean(false);
        filter.filter(ex1, ex -> {
            pass1.set(true);
            return Mono.empty();
        }).block();
        assertTrue(pass1.get(), "首次请求应通过");

        // 重放第二次请求，携带相同的 Nonce
        MockServerHttpRequest req2 = MockServerHttpRequest.get("/api/open/data")
                .header(GatewayConstants.X_APP_ID, "app-01")
                .header(GatewayConstants.X_TIMESTAMP, String.valueOf(now))
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .build();
        MockServerWebExchange ex2 = MockServerWebExchange.from(req2);

        AtomicBoolean pass2 = new AtomicBoolean(false);
        filter.filter(ex2, ex -> {
            pass2.set(true);
            return Mono.empty();
        }).block();
        assertFalse(pass2.get(), "重复 Nonce 应被拦截");
        assertEquals(HttpStatus.UNAUTHORIZED, ex2.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("测试被篡改的签名被拦截")
    void testTamperedSignatureBlocksWith401() {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        provider.registerSecret("app-01", "secret");

        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        long now = System.currentTimeMillis();
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/open/data")
                .header(GatewayConstants.X_APP_ID, "app-01")
                .header(GatewayConstants.X_TIMESTAMP, String.valueOf(now))
                .header(GatewayConstants.X_NONCE, "nonce-1")
                .header(GatewayConstants.X_SIGNATURE, "tampered-bad-signature")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("测试开启请求体验签时合法 Body 签名请求顺利通过校验且下游可正常读取缓存 Body")
    void testValidBodySignaturePasses() throws Exception {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setBodyEnabled(true);

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        provider.registerSecret("app-test-01", "my-secret-key-12345");

        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        long now = System.currentTimeMillis();
        String nonce = "nonce-body-001";
        String body = "{\"name\":\"ateng\",\"role\":\"admin\"}";
        String bodyHash = calculateSha256(body.getBytes(StandardCharsets.UTF_8));
        String canonical = "appId=app-test-01&bodyHash=" + bodyHash + "&nonce=" + nonce + "&timestamp=" + now;
        String signature = calculateHmac(canonical, "my-secret-key-12345");

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/open/data")
                .header(GatewayConstants.X_APP_ID, "app-test-01")
                .header(GatewayConstants.X_TIMESTAMP, String.valueOf(now))
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .body(body);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            executed.set(true);
            // 验证下游可完整读取缓存后的请求体
            return ex.getRequest().getBody().collectList().flatMap(buffers -> {
                assertEquals(1, buffers.size());
                return Mono.empty();
            });
        }).block();

        assertTrue(executed.get(), "签名与请求体摘要合法应放行请求");
    }

    @Test
    @DisplayName("测试篡改请求体内容被拦截并确定性释放堆外内存")
    void testTamperedBodyBlocksWith401AndReleasesBuffer() throws Exception {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setBodyEnabled(true);

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        provider.registerSecret("app-test-01", "my-secret-key-12345");

        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        long now = System.currentTimeMillis();
        String nonce = "nonce-body-002";
        String originalBody = "{\"name\":\"ateng\",\"role\":\"admin\"}";
        String bodyHash = calculateSha256(originalBody.getBytes(StandardCharsets.UTF_8));
        String canonical = "appId=app-test-01&bodyHash=" + bodyHash + "&nonce=" + nonce + "&timestamp=" + now;
        String signature = calculateHmac(canonical, "my-secret-key-12345");

        // 发送被恶意篡改的请求体
        String tamperedBody = "{\"name\":\"ateng\",\"role\":\"hacker\"}";
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/open/data")
                .header(GatewayConstants.X_APP_ID, "app-test-01")
                .header(GatewayConstants.X_TIMESTAMP, String.valueOf(now))
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .body(tamperedBody);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            executed.set(true);
            return Mono.empty();
        }).block();

        assertFalse(executed.get(), "被篡改的请求体应被拦截");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        // 强断言：验签失败后确定性释放 CACHED_REQUEST_BODY_ATTR 避免堆外内存泄露
        assertNull(exchange.getAttribute(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR));
    }

    @Test
    @DisplayName("测试 Content-Length 超过 2MB 快速熔断为 HTTP 413 Payload Too Large")
    void testContentLengthExceeds2MBBlocksWith413() {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setBodyEnabled(true);

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/open/data")
                .header(org.springframework.http.HttpHeaders.CONTENT_LENGTH, String.valueOf(3 * 1024 * 1024))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            executed.set(true);
            return Mono.empty();
        }).block();

        assertFalse(executed.get());
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("测试开启请求体验签时空 Body POST 请求安全放行且签名不包含 bodyHash")
    void testEmptyBodyPostPassesWithoutBodyHash() throws Exception {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setBodyEnabled(true);

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        provider.registerSecret("app-test-01", "my-secret-key-12345");

        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        long now = System.currentTimeMillis();
        String nonce = "nonce-empty-body";
        // 空 Body 签名串不包含 &bodyHash=...，完全向后兼容
        String canonical = "appId=app-test-01&nonce=" + nonce + "&timestamp=" + now;
        String signature = calculateHmac(canonical, "my-secret-key-12345");

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/open/data")
                .header(GatewayConstants.X_APP_ID, "app-test-01")
                .header(GatewayConstants.X_TIMESTAMP, String.valueOf(now))
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            executed.set(true);
            return Mono.empty();
        }).block();

        assertTrue(executed.get(), "空请求体应正常放行");
    }

    @Test
    @DisplayName("测试未开启请求体验签 (bodyEnabled=false) 时 POST 请求即使携带 Body 仍按无 BodyHash 格式验签")
    void testBodyDisabledBackwardCompatible() throws Exception {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setBodyEnabled(false);

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        provider.registerSecret("app-test-01", "my-secret-key-12345");

        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        long now = System.currentTimeMillis();
        String nonce = "nonce-backward-001";
        // 未开启 bodyEnabled，签名串不包含 bodyHash
        String canonical = "appId=app-test-01&nonce=" + nonce + "&timestamp=" + now;
        String signature = calculateHmac(canonical, "my-secret-key-12345");

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/open/data")
                .header(GatewayConstants.X_APP_ID, "app-test-01")
                .header(GatewayConstants.X_TIMESTAMP, String.valueOf(now))
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .body("{\"key\":\"value\"}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            executed.set(true);
            return Mono.empty();
        }).block();

        assertTrue(executed.get(), "未开启 bodyEnabled 应向后兼容顺利放行");
    }

    @Test
    @DisplayName("测试开启请求体验签时合法 PUT Body 签名请求顺利通过校验且下游可正常读取缓存 Body")
    void testValidBodySignaturePutPasses() throws Exception {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setBodyEnabled(true);

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        provider.registerSecret("app-test-01", "my-secret-key-12345");

        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        long now = System.currentTimeMillis();
        String nonce = "nonce-put-001";
        String body = "{\"action\":\"update\",\"status\":\"active\"}";
        String bodyHash = calculateSha256(body.getBytes(StandardCharsets.UTF_8));
        String canonical = "appId=app-test-01&bodyHash=" + bodyHash + "&nonce=" + nonce + "&timestamp=" + now;
        String signature = calculateHmac(canonical, "my-secret-key-12345");

        MockServerHttpRequest request = MockServerHttpRequest.put("/api/open/update")
                .header(GatewayConstants.X_APP_ID, "app-test-01")
                .header(GatewayConstants.X_TIMESTAMP, String.valueOf(now))
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .body(body);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            executed.set(true);
            return ex.getRequest().getBody().collectList().flatMap(buffers -> {
                assertEquals(1, buffers.size());
                return Mono.empty();
            });
        }).block();

        assertTrue(executed.get(), "PUT 请求签名与请求体摘要合法应放行请求");
    }

    @Test
    @DisplayName("测试开启请求体验签时 PATCH 请求体被篡改触发 401 拦截并确定性释放堆外内存")
    void testTamperedBodyPatchBlocksWith401() throws Exception {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setBodyEnabled(true);

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        provider.registerSecret("app-test-01", "my-secret-key-12345");

        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        long now = System.currentTimeMillis();
        String nonce = "nonce-patch-001";
        String originalBody = "{\"patchField\":\"original\"}";
        String bodyHash = calculateSha256(originalBody.getBytes(StandardCharsets.UTF_8));
        String canonical = "appId=app-test-01&bodyHash=" + bodyHash + "&nonce=" + nonce + "&timestamp=" + now;
        String signature = calculateHmac(canonical, "my-secret-key-12345");

        // 发送被篡改的 PATCH 请求体
        String tamperedBody = "{\"patchField\":\"tampered\"}";
        MockServerHttpRequest request = MockServerHttpRequest.patch("/api/open/patch")
                .header(GatewayConstants.X_APP_ID, "app-test-01")
                .header(GatewayConstants.X_TIMESTAMP, String.valueOf(now))
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .body(tamperedBody);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            executed.set(true);
            return Mono.empty();
        }).block();

        assertFalse(executed.get(), "被篡改的 PATCH 请求体应被拦截");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertNull(exchange.getAttribute(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR), "堆外内存应确定性释放");
    }

    @Test
    @DisplayName("测试未开启请求体验签 (bodyEnabled=false) 时超过 2MB 的请求不会被 413 拦截（向后兼容）")
    void testBodyDisabledWithLargeBodyPassesToNextFilter() throws Exception {
        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setBodyEnabled(false);

        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        provider.registerSecret("app-test-01", "my-secret-key-12345");

        GatewaySignGuardFilter filter = new GatewaySignGuardFilter(props, provider, objectMapper);

        long now = System.currentTimeMillis();
        String nonce = "nonce-large-001";
        String canonical = "appId=app-test-01&nonce=" + nonce + "&timestamp=" + now;
        String signature = calculateHmac(canonical, "my-secret-key-12345");

        // 模拟超过 2MB 的普通写请求（Content-Length = 3MB）
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/open/upload")
                .header(GatewayConstants.X_APP_ID, "app-test-01")
                .header(GatewayConstants.X_TIMESTAMP, String.valueOf(now))
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .header(org.springframework.http.HttpHeaders.CONTENT_LENGTH, String.valueOf(3 * 1024 * 1024))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            executed.set(true);
            return Mono.empty();
        }).block();

        assertTrue(executed.get(), "未开启 bodyEnabled 时即使报文超过 2MB 也不应在签名层提前被 413 熔断，保持向后兼容");
    }
}
