package io.github.atengk.cloud.sample.gateway;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.route.GatewayRouteRefreshListener;
import io.github.atengk.cloud.gateway.route.NacosRouteDefinitionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关双轨动态路由（独立 JSON 仓库与主配置文件）端到端集成测试 (100% 离线自洽)
 *
 * <p>全面覆盖以下生产动态治理核心语义：
 * <ul>
 *     <li><b>Track A (独立 JSON 仓库)</b>：404 -> 200 无感热更、畸变报文快照守卫容错与路由安全下线；</li>
 *     <li><b>Track B (主配置环境变量)</b>：环境变量注入新路由触发 {@link EnvironmentChangeEvent}，无感热更即时生效；</li>
 *     <li><b>主配置快照容错</b>：注入缺少协议头的破损路由时，快照守卫生效坚决保留上一份有效路由，服务不中断；</li>
 *     <li><b>双轨多源共存</b>：静态路由、独立数据源动态路由与主配置动态路由三者协同生效，互不冲刷。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class SampleGatewayDynamicRouteIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private NacosRouteDefinitionRepository nacosRouteDefinitionRepository;

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @AfterEach
    void cleanUp() {
        // 测试结束后确定性清理动态路由与环境属性源，防止跨用例与类级上下文污染
        try {
            nacosRouteDefinitionRepository.delete(Mono.just("dynamic-order-route")).block();
        } catch (Exception ignored) {
            // 忽略未找到异常
        }
        if (environment != null && environment.getPropertySources().contains("test")) {
            environment.getPropertySources().remove("test");
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

    @Test
    @DisplayName("测试主配置动态路由：环境变量增量热绑 (404 -> 200)、畸变路由快照守卫容错与双轨多源共存")
    void testMainConfigRouteHotReloadAndSnapshotGuard() {
        // 1. 初始未配置该主配置动态路由时，请求 /dynamic/main-hot/test 返回 404 Not Found
        webTestClient.get()
                .uri("/dynamic/main-hot/test")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo(404);

        // 2. 模拟外部配置刷新，向 Spring Environment 注入主配置路由定义 (包含现有基线路由与新增热更路由)
        TestPropertyValues.of(
                "spring.cloud.gateway.routes[0].id=mock-test-route",
                "spring.cloud.gateway.routes[0].uri=forward:/mock/hello",
                "spring.cloud.gateway.routes[0].predicates[0]=Path=/api/mock/**",
                "spring.cloud.gateway.routes[1].id=main-hot-route",
                "spring.cloud.gateway.routes[1].uri=forward:/mock/hello",
                "spring.cloud.gateway.routes[1].predicates[0]=Path=/dynamic/main-hot/**"
        ).applyTo(environment);

        // 广播 EnvironmentChangeEvent 事件
        applicationEventPublisher.publishEvent(new EnvironmentChangeEvent(
                Set.of("spring.cloud.gateway.routes[1].id", "spring.cloud.gateway.routes[1].uri")));

        // 3. 强断言：主配置动态路由原子生效 (404 -> 200)
        assertDynamicMainHotReturnsOk();

        // 4. 双轨共存验证：同时激活 Track A 独立 Nacos 动态路由
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
        nacosRouteDefinitionRepository.updateRoutes(validRouteJson);

        // 断言：Track A 与 Track B 路由同时正常工作，互不干扰
        webTestClient.get()
                .uri("/dynamic/order/1001")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200);

        assertDynamicMainHotReturnsOk();

        // 5. 模拟主配置推送非法路由 (缺少 scheme 协议头)
        TestPropertyValues.of(
                "spring.cloud.gateway.routes[0].id=mock-test-route",
                "spring.cloud.gateway.routes[0].uri=forward:/mock/hello",
                "spring.cloud.gateway.routes[0].predicates[0]=Path=/api/mock/**",
                "spring.cloud.gateway.routes[1].id=corrupted-route",
                "spring.cloud.gateway.routes[1].uri=/no-scheme-uri",
                "spring.cloud.gateway.routes[1].predicates[0]=Path=/dynamic/main-hot/**"
        ).applyTo(environment);

        applicationEventPublisher.publishEvent(new EnvironmentChangeEvent(
                Set.of("spring.cloud.gateway.routes[1].uri")));

        // 强断言：快照守卫拒绝脏配置，现有生效路由依然稳定服务 (依然返回 200)
        assertDynamicMainHotReturnsOk();
    }

    private void assertDynamicMainHotReturnsOk() {
        webTestClient.get()
                .uri("/dynamic/main-hot/test")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo("gateway-ok");
    }
}
