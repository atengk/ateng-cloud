package io.github.atengk.cloud.sample.gateway;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.loadbalancer.GrayServiceInstanceListSupplier;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关全域生态能力集成测试 (100% 离线自洽)
 *
 * <p>全面覆盖以下生产生态与流量治理语义：
 * <ul>
 *     <li>等保三级安全响应头强化：验证出站自动注入 X-Content-Type-Options, X-Frame-Options 等标头；</li>
 *     <li>流量染色全链路透传：测试 Header 及 Query 染色标签透传至下游微服务；</li>
 *     <li>金丝雀灰度分流与平滑降级：测试灰度节点精准匹配与灰度全下线时的无损降级；</li>
 *     <li>OpenAPI 接口文档守卫：测试文档统一入口与守卫机制。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "15000")
public class SampleGatewayEcosystemIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("测试 OWASP 等保三级合规安全响应头出站自动强化")
    void testSecurityHeadersInjected() {
        webTestClient.get()
                .uri("/api/mock/hello")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "SAMEORIGIN")
                .expectHeader().valueEquals("X-XSS-Protection", "1; mode=block")
                .expectHeader().valueEquals("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
                .expectHeader().valueEquals("Referrer-Policy", "strict-origin-when-cross-origin")
                .expectHeader().exists(GatewayConstants.X_TRACE_ID);
    }

    @Test
    @DisplayName("测试 X-Traffic-Tag 标头染色透明透传至下游微服务")
    void testTrafficTagHeaderPropagation() {
        webTestClient.get()
                .uri("/api/mock/echo-headers")
                .header(GatewayConstants.X_TRAFFIC_TAG, "canary-v2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data['x-traffic-tag']").isEqualTo("canary-v2");
    }

    @Test
    @DisplayName("测试 URL Query 参数 traffic_tag 转译为 X-Traffic-Tag 标头透传至下游")
    void testTrafficTagQueryPropagation() {
        webTestClient.get()
                .uri("/api/mock/echo-headers?traffic_tag=canary-query-tag")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data['x-traffic-tag']").isEqualTo("canary-query-tag");
    }

    @Test
    @DisplayName("测试 Header 优先级高于 Query 参数染色标签")
    void testTrafficTagHeaderPrecedenceOverQuery() {
        webTestClient.get()
                .uri("/api/mock/echo-headers?traffic_tag=query-fallback")
                .header(GatewayConstants.X_TRAFFIC_TAG, "header-priority")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data['x-traffic-tag']").isEqualTo("header-priority");
    }

    @Test
    @DisplayName("测试无染色标签请求下游不接收 x-traffic-tag 标头")
    void testNoTrafficTagPassthrough() {
        webTestClient.get()
                .uri("/api/mock/echo-headers")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data['x-traffic-tag']").doesNotExist();
    }

    @Test
    @DisplayName("测试金丝雀灰度分流与无损平滑降级机制")
    void testGrayRoutingAndFallback() {
        AtengGatewayTrafficProperties properties = new AtengGatewayTrafficProperties();
        properties.setEnabled(true);
        properties.setFallbackToDefault(true);

        // 构造测试服务节点：2 个基线节点，1 个 v2 灰度节点
        Map<String, String> metaGray = new HashMap<>();
        metaGray.put("tag", "v2");
        ServiceInstance grayInstance = new DefaultServiceInstance("inst-gray", "order-service", "10.0.0.1", 8080, false, metaGray);

        ServiceInstance base1 = new DefaultServiceInstance("inst-base-1", "order-service", "10.0.0.2", 8080, false, Map.of("tag", "default"));
        ServiceInstance base2 = new DefaultServiceInstance("inst-base-2", "order-service", "10.0.0.3", 8080, false, Collections.emptyMap());

        List<ServiceInstance> allInstances = List.of(grayInstance, base1, base2);

        ServiceInstanceListSupplier delegate = new ServiceInstanceListSupplier() {
            @Override
            public String getServiceId() {
                return "order-service";
            }

            @Override
            public Flux<List<ServiceInstance>> get() {
                return Flux.just(allInstances);
            }
        };

        GrayServiceInstanceListSupplier supplier = new GrayServiceInstanceListSupplier(delegate, properties);

        // 1. 灰度标签流量精准命中灰度节点
        List<ServiceInstance> grayResults = supplier.filterByTag(allInstances, "v2");
        assertEquals(1, grayResults.size());
        assertEquals("inst-gray", grayResults.get(0).getInstanceId());

        // 2. 基线未染色流量优先分配基线节点
        List<ServiceInstance> baseResults = supplier.filterByTag(allInstances, null);
        assertEquals(2, baseResults.size());
        assertTrue(baseResults.stream().allMatch(i -> i.getInstanceId().startsWith("inst-base")));

        // 3. 灰度节点全部下线（请求 v3 标签，但集群无 v3 节点），平滑降级至基线节点，杜绝 503
        List<ServiceInstance> fallbackResults = supplier.filterByTag(allInstances, "v3");
        assertEquals(2, fallbackResults.size(), "灰度节点缺失时应平滑降级至基线默认实例");
        assertTrue(fallbackResults.stream().allMatch(i -> i.getInstanceId().startsWith("inst-base")));

        // 4. 若配置关闭降级 (fallbackToDefault=false)，阻断流量返回空集合
        properties.setFallbackToDefault(false);
        List<ServiceInstance> blockedResults = supplier.filterByTag(allInstances, "v3");
        assertTrue(blockedResults.isEmpty(), "关闭降级时未匹配到灰度节点应返回空集合");
    }

    @Test
    @DisplayName("测试 OpenAPI 守卫默认 PUBLIC 模式下放行访问并重定向至 Swagger UI")
    void testOpenApiPublicModePassThrough() {
        // 在 sample-cloud-gateway 中默认 guardMode 为 PUBLIC，访问 /docs 自动重定向至 Swagger UI (HTTP 302 Found)
        webTestClient.get()
                .uri("/docs")
                .exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/swagger-ui.html")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
    }

    @Test
    @DisplayName("测试开放接口缺少必需签名标头被 401 阻断")
    void testSignGuardMissingHeadersBlocked() {
        webTestClient.get()
                .uri("/api/open/data")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo(401)
                .jsonPath("$.msg").value(containsString("缺少签名"));
    }

    @Test
    @DisplayName("测试开放接口合法 HMAC-SHA256 签名校验通过")
    void testSignGuardValidSignaturePasses() throws Exception {
        String appId = "sample-app";
        String secret = "sample-secret-123456";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce-" + System.nanoTime();

        // 规范签名串: appId + nonce + timestamp (无 query 参数)
        String canonical = "appId=" + appId + "&nonce=" + nonce + "&timestamp=" + timestamp;
        String signature = calculateHmacSha256(canonical, secret);

        webTestClient.get()
                .uri("/api/open/data")
                .header(GatewayConstants.X_APP_ID, appId)
                .header(GatewayConstants.X_TIMESTAMP, timestamp)
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo("open-api-verified");
    }

    @Test
    @DisplayName("测试开放接口 Nonce 重放攻击被防御性阻断")
    void testSignGuardReplayAttackBlocked() throws Exception {
        String appId = "sample-app";
        String secret = "sample-secret-123456";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = "replay-nonce-" + System.nanoTime();

        String canonical = "appId=" + appId + "&nonce=" + nonce + "&timestamp=" + timestamp;
        String signature = calculateHmacSha256(canonical, secret);

        // 首次请求成功
        webTestClient.get()
                .uri("/api/open/data")
                .header(GatewayConstants.X_APP_ID, appId)
                .header(GatewayConstants.X_TIMESTAMP, timestamp)
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .exchange()
                .expectStatus().isOk();

        // 相同 Nonce 二次重放请求应被 401 阻断
        webTestClient.get()
                .uri("/api/open/data")
                .header(GatewayConstants.X_APP_ID, appId)
                .header(GatewayConstants.X_TIMESTAMP, timestamp)
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo(401)
                .jsonPath("$.msg").value(containsString("重放攻击"));
    }

    @Test
    @DisplayName("测试开放接口签名时间戳偏差超限被 401 阻断")
    void testSignGuardExpiredTimestampBlocked() throws Exception {
        String appId = "sample-app";
        String secret = "sample-secret-123456";
        // 构造 10 分钟前的过期时间戳 (阈值为 5 分钟)
        String expiredTimestamp = String.valueOf(System.currentTimeMillis() - 600_000L);
        String nonce = "nonce-" + System.nanoTime();

        String canonical = "appId=" + appId + "&nonce=" + nonce + "&timestamp=" + expiredTimestamp;
        String signature = calculateHmacSha256(canonical, secret);

        webTestClient.get()
                .uri("/api/open/data")
                .header(GatewayConstants.X_APP_ID, appId)
                .header(GatewayConstants.X_TIMESTAMP, expiredTimestamp)
                .header(GatewayConstants.X_NONCE, nonce)
                .header(GatewayConstants.X_SIGNATURE, signature)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo(401)
                .jsonPath("$.msg").value(containsString("时间戳偏差过大"));
    }

    private String calculateHmacSha256(String data, String key) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
