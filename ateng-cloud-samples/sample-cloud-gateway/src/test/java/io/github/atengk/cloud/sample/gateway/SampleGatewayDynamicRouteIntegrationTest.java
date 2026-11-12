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
        for (String routeId : new String[]{"dynamic-order-route", "shared-precedence-route"}) {
            try {
                nacosRouteDefinitionRepository.delete(Mono.just(routeId)).block();
            } catch (Exception ignored) {
                // 忽略未找到异常
            }
        }
        if (environment != null && environment.getPropertySources().contains("test")) {
            environment.getPropertySources().remove("test");
            applicationEventPublisher.publishEvent(new EnvironmentChangeEvent(Set.of("spring.cloud.gateway.routes")));
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

        // 2. 模拟 Nacos 推送标准合法 JSON 动态路由 (映射到 /sample/cloud/gateway/security/path-guard)
        String validRouteJson = """
                [
                  {
                    "id": "dynamic-order-route",
                    "uri": "forward:/sample/cloud/gateway/security/path-guard",
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
                .jsonPath("$.data.defenseStatus").isEqualTo("PATH_NORMALIZED");

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
                .jsonPath("$.data.defenseStatus").isEqualTo("PATH_NORMALIZED");

        // 6. 强断言：本地 YAML 静态路由兜底与共存 (静态配置的 /api/security/path-guard 依然正常服务)
        webTestClient.get()
                .uri("/api/security/path-guard")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.defenseStatus").isEqualTo("PATH_NORMALIZED");

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
                "spring.cloud.gateway.routes[0].id=security-path-guard",
                "spring.cloud.gateway.routes[0].uri=forward:/sample/cloud/gateway/security/path-guard",
                "spring.cloud.gateway.routes[0].predicates[0]=Path=/api/security/path-guard",
                "spring.cloud.gateway.routes[1].id=main-hot-route",
                "spring.cloud.gateway.routes[1].uri=forward:/sample/cloud/gateway/security/path-guard",
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
                    "uri": "forward:/sample/cloud/gateway/security/path-guard",
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
                "spring.cloud.gateway.routes[0].id=security-path-guard",
                "spring.cloud.gateway.routes[0].uri=forward:/sample/cloud/gateway/security/path-guard",
                "spring.cloud.gateway.routes[0].predicates[0]=Path=/api/security/path-guard",
                "spring.cloud.gateway.routes[1].id=corrupted-route",
                "spring.cloud.gateway.routes[1].uri=/no-scheme-uri",
                "spring.cloud.gateway.routes[1].predicates[0]=Path=/dynamic/main-hot/**"
        ).applyTo(environment);

        applicationEventPublisher.publishEvent(new EnvironmentChangeEvent(
                Set.of("spring.cloud.gateway.routes[1].uri")));

        // 强断言：快照守卫拒绝脏配置，现有生效路由依然稳定服务 (依然返回 200)
        assertDynamicMainHotReturnsOk();
    }

    @Test
    @DisplayName("测试路由定义全量查询端点与动态热更刷新事件发布")
    void testQueryRouteDefinitionsAndRefreshEvent() {
        // 1. 查询当前网关生效的全量路由定义列表
        webTestClient.get()
                .uri("/api/route/definitions")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isArray()
                .jsonPath("$.data[?(@.routeId == 'order-dispatch')]").isNotEmpty()
                .jsonPath("$.data[?(@.routeId == 'auth-login')]").isNotEmpty();

        // 2. 触发动态路由刷新事件端点
        webTestClient.post()
                .uri("/api/route/refresh")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.status").isEqualTo("SUCCESS")
                .jsonPath("$.data.snapshotStatus").isEqualTo("HEALTHY")
                .jsonPath("$.data.activeRouteCount").isNumber();
    }

    @Test
    @DisplayName("测试电商订单路由分发与金丝雀灰度染色标签多维决议")
    void testOrderDispatchWithCanaryTagResolution() {
        // 1. 携带 HTTP 请求头 X-Traffic-Tag 触发金丝雀灰度分发
        webTestClient.get()
                .uri("/api/order/dispatch?orderId=ORD-CANARY-1001")
                .header(GatewayConstants.X_TRAFFIC_TAG, "canary-v2")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.orderId").isEqualTo("ORD-CANARY-1001")
                .jsonPath("$.data.serviceId").isEqualTo("order-service")
                .jsonPath("$.data.trafficTag").isEqualTo("canary-v2")
                .jsonPath("$.data.status").isEqualTo("DISPATCHED_TO_CANARY")
                .jsonPath("$.data.fallbackToBaseline").isEqualTo(false);

        // 2. 兜底通过 URL 查询参数 traffic_tag 触发金丝雀灰度分发
        webTestClient.get()
                .uri("/api/order/dispatch?orderId=ORD-CANARY-1002&traffic_tag=canary-v2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.trafficTag").isEqualTo("canary-v2")
                .jsonPath("$.data.status").isEqualTo("DISPATCHED_TO_CANARY");

        // 3. 未携带任何标签时默认分发至基线实例
        webTestClient.get()
                .uri("/api/order/dispatch?orderId=ORD-BASE-2001")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.orderId").isEqualTo("ORD-BASE-2001")
                .jsonPath("$.data.trafficTag").isEqualTo("baseline")
                .jsonPath("$.data.status").isEqualTo("DISPATCHED_TO_BASELINE");

        // 4. 模拟灰度节点全下线时平滑回退至基线实例
        webTestClient.get()
                .uri("/api/order/fallback?orderId=ORD-FALLBACK-3001")
                .header(GatewayConstants.X_TRAFFIC_TAG, "canary-v2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.status").isEqualTo("FALLBACK_TO_BASELINE")
                .jsonPath("$.data.fallbackToBaseline").isEqualTo(true);
    }

    @Test
    @DisplayName("测试双轨路由同名覆盖优先级与全景诊断端点快照健康度联动")
    void testDualTrackPrecedenceAndPanoramaSnapshotHealth() {
        // 1. 激活主配置基线路由 shared-precedence-route -> path-guard
        TestPropertyValues.of(
                "spring.cloud.gateway.routes[0].id=shared-precedence-route",
                "spring.cloud.gateway.routes[0].uri=forward:/sample/cloud/gateway/security/path-guard",
                "spring.cloud.gateway.routes[0].predicates[0]=Path=/dynamic/precedence/**"
        ).applyTo(environment);
        applicationEventPublisher.publishEvent(new EnvironmentChangeEvent(
                Set.of("spring.cloud.gateway.routes[0].id", "spring.cloud.gateway.routes[0].uri")));

        webTestClient.get()
                .uri("/dynamic/precedence/check")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.defenseStatus").isEqualTo("PATH_NORMALIZED");

        // 2. Nacos 独立动态路由定义同名 shared-precedence-route -> route/definitions，验证独立动态路由权威覆盖主配置路由
        String overrideJson = """
                [
                  {
                    "id": "shared-precedence-route",
                    "uri": "forward:/sample/cloud/gateway/route/definitions",
                    "predicates": [
                      {
                        "name": "Path",
                        "args": {
                          "_genkey_0": "/dynamic/precedence/**"
                        }
                      }
                    ]
                  }
                ]
                """;
        assertTrue(nacosRouteDefinitionRepository.updateRoutes(overrideJson));

        webTestClient.get()
                .uri("/dynamic/precedence/check")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isArray();

        // 3. 推送畸变 Nacos 路由配置：Last-Known Good Snapshot 兜底生效，全景诊断准确切换为 DEGRADED
        assertFalse(nacosRouteDefinitionRepository.updateRoutes("[ { \"id\": \"corrupted\", \"uri\": "));

        webTestClient.get()
                .uri("/dynamic/precedence/check")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isArray();

        webTestClient.get()
                .uri("/actuator/atengGateway")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DEGRADED")
                .jsonPath("$.dynamicRoute.nacosRoute.snapshotHealthy").isEqualTo(false)
                .jsonPath("$.dynamicRoute.nacosRoute.lastRefreshError").isNotEmpty();

        // 4. 恢复合法 Nacos 路由并清理后，全景诊断恢复 UP
        assertTrue(nacosRouteDefinitionRepository.updateRoutes(overrideJson));
        webTestClient.get()
                .uri("/actuator/atengGateway")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.dynamicRoute.nacosRoute.snapshotHealthy").isEqualTo(true);

        // 5. 推送破损主配置路由：主配置快照守卫回滚并由全景诊断反映 DEGRADED
        TestPropertyValues.of(
                "spring.cloud.gateway.routes[0].id=shared-precedence-route",
                "spring.cloud.gateway.routes[0].uri=/missing-scheme",
                "spring.cloud.gateway.routes[0].predicates[0]=Path=/dynamic/precedence/**"
        ).applyTo(environment);
        applicationEventPublisher.publishEvent(new EnvironmentChangeEvent(
                Set.of("spring.cloud.gateway.routes[0].uri")));

        webTestClient.get()
                .uri("/actuator/atengGateway")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DEGRADED")
                .jsonPath("$.dynamicRoute.mainConfigRoute.snapshotHealthy").isEqualTo(false)
                .jsonPath("$.dynamicRoute.mainConfigRoute.lastRefreshError").isNotEmpty();
    }

    private void assertDynamicMainHotReturnsOk() {
        webTestClient.get()
                .uri("/dynamic/main-hot/test")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.defenseStatus").isEqualTo("PATH_NORMALIZED");
    }
}
