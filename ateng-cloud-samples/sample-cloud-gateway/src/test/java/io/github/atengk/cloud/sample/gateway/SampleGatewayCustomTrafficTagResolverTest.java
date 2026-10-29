package io.github.atengk.cloud.sample.gateway;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.spi.TrafficTagResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 自定义 TrafficTagResolver SPI 动态打标与伪造标头清洗端到端集成测试 (100% 离线自洽)
 *
 * <p>验证自定义 TrafficTagResolver 能够：
 * 1. 平滑接管默认解析器；
 * 2. 结合业务自定义规则（如 VIP 用户等级）动态赋予染色泳道；
 * 3. 对未决议出标签的外部请求强制剥离伪造标头（防链路穿透）；
 * 4. 强制以决议出的权威标签重写并覆盖外部请求头。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "15000")
@Import(SampleGatewayCustomTrafficTagResolverTest.CustomTrafficTagResolverConfig.class)
public class SampleGatewayCustomTrafficTagResolverTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("测试自定义 SPI 动态打标：VIP 用户等级自动染色为 vip-lane")
    void testDynamicTaggingByCustomResolver() {
        webTestClient.get()
                .uri("/api/mock/echo-headers")
                .header("X-User-Tier", "VIP")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data['x-traffic-tag']").isEqualTo("vip-lane");
    }

    @Test
    @DisplayName("测试自定义 SPI 权威覆盖：外部传入陈旧标头被权威决议器强制纠正")
    void testCustomResolverOverridesForgedHeader() {
        webTestClient.get()
                .uri("/api/mock/echo-headers")
                .header("X-User-Tier", "VIP")
                .header(GatewayConstants.X_TRAFFIC_TAG, "forged-external-tag")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data['x-traffic-tag']").isEqualTo("vip-lane");
    }

    @Test
    @DisplayName("测试非 VIP 请求且私自伪造染色标头：决议器返回空，伪造标头被物理剥离")
    void testForgedHeaderStrippedWhenUnresolved() {
        webTestClient.get()
                .uri("/api/mock/echo-headers")
                .header("X-User-Tier", "STANDARD")
                .header(GatewayConstants.X_TRAFFIC_TAG, "spoofed-canary-tag")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data['x-traffic-tag']").doesNotExist();
    }

    @TestConfiguration
    static class CustomTrafficTagResolverConfig {
        @Bean
        public TrafficTagResolver customTrafficTagResolver() {
            return exchange -> {
                if (exchange == null || exchange.getRequest() == null) {
                    return null;
                }
                // 自定义业务规则：根据 X-User-Tier 标头决议染色
                String userTier = exchange.getRequest().getHeaders().getFirst("X-User-Tier");
                if ("VIP".equalsIgnoreCase(userTier)) {
                    return "vip-lane";
                }
                // 非 VIP 用户不打标，返回 null 触发伪造标头物理剥离清洗
                return null;
            };
        }
    }
}
