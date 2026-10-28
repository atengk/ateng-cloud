package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import io.github.atengk.cloud.gateway.provider.InMemorySignSecretProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
