package io.github.atengk.cloud.sample.gateway;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.route.NacosRouteDefinitionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关 Nacos 动态路由持久化、快照守卫容错与无感原子热更端到端集成测试 (100% 离线自洽)
 *
 * <p>全面覆盖以下生产动态治理核心语义：
 * <ul>
 *     <li>初始未配置动态路由时请求返回 HTTP 404 Not Found；</li>
 *     <li>模拟推送标准 JSON 动态路由，即时生效（404 -> 200 无感热更）；</li>
 *     <li>模拟推送畸变破损 JSON 报文，快照守卫生效坚决保留上一份有效快照，路由依然可用；</li>
 *     <li>动态路由原子刷新过程中，本地 YAML 静态路由持续稳定提供服务（多源共存不被冲掉）；</li>
 *     <li>动态路由安全下线删除后，对应路径平滑回退为 404。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public class SampleGatewayDynamicRouteIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private NacosRouteDefinitionRepository nacosRouteDefinitionRepository;

    @AfterEach
    void cleanUp() {
        // 测试结束后确定性清理动态路由，防止跨用例污染
        try {
            nacosRouteDefinitionRepository.delete(Mono.just("dynamic-order-route")).block();
        } catch (Exception ignored) {
            // 忽略未找到异常
        }
    }

    @Test
    @DisplayName("测试动态路由端到端全链路：404 -> 200原子热生效、畸变报文快照守卫容错与本地静态路由共存")
    void testDynamicRouteLifecycleAndSnapshotGuard() {
        // 1. 初始未配置动态路由时，请求 /dynamic/order/1001 返回 404 Not Found
        webTestClient.get()
                .uri("/dynamic/order/1001")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(404);

        // 2. 模拟 Nacos 推送标准合法 JSON 动态路由 (映射到 /mock/hello)
        String validRouteJson = """
                [
                  {
                    "id": "dynamic-order-route",
                    "uri": "forward:/mock/hello",
                    "predicates": [
                      {
                        "name": "Path",
                        "args": {
                          "_genkey_0": "/dynamic/order/**"
                        }
                      }
                    ]
                  }
                ]
                """;

        boolean updateSuccess = nacosRouteDefinitionRepository.updateRoutes(validRouteJson);
        assertTrue(updateSuccess, "合法 JSON 动态路由更新应成功");

        // 3. 强断言：动态路由即时热生效 (404 -> 200)
        webTestClient.get()
                .uri("/dynamic/order/1001")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo("gateway-ok");

        // 4. 模拟运维失误推送畸形破损的 JSON 报文
        String brokenRouteJson = "[ { \"id\": \"corrupted-route\", \"uri\": ";
        boolean brokenUpdateSuccess = nacosRouteDefinitionRepository.updateRoutes(brokenRouteJson);
        assertFalse(brokenUpdateSuccess, "畸变 JSON 路由应被快照守卫拒绝");

        // 5. 强断言：快照守卫容错机制生效，之前生效的动态路由依然维持正常工作 (依然返回 200)
        webTestClient.get()
                .uri("/dynamic/order/1001")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo("gateway-ok");

        // 6. 强断言：本地 YAML 静态路由兜底与共存 (静态配置的 /api/mock/hello 依然正常服务)
        webTestClient.get()
                .uri("/api/mock/hello")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo("gateway-ok");

        // 7. 删除动态路由后，路径平滑恢复为 404
        nacosRouteDefinitionRepository.delete(Mono.just("dynamic-order-route")).block();

        webTestClient.get()
                .uri("/dynamic/order/1001")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo(404);
    }
}
