package io.github.atengk.cloud.gateway.actuator;

import io.github.atengk.cloud.gateway.properties.AtengGatewayProperties;
import io.github.atengk.cloud.gateway.route.GatewayRouteRefreshListener;
import io.github.atengk.cloud.gateway.route.NacosRouteDefinitionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关全景健康与动态路由诊断端点单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class GatewayPanoramaEndpointTest {

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    private RouteDefinition createRoute(String id, String uri) {
        RouteDefinition route = new RouteDefinition();
        route.setId(id);
        route.setUri(URI.create(uri));
        return route;
    }

    @Test
    @DisplayName("测试四维聚合诊断报告全量输出与统计脱敏")
    void testReportWithAllComponentsAvailable() {
        AtengGatewayProperties props = new AtengGatewayProperties();
        props.getDynamicRoute().setEnabled(true);
        props.getDynamicRoute().setDataId("gateway-routes.json");
        props.getDynamicRoute().setGroup("DEFAULT_GROUP");
        props.getTraffic().setEnabled(true);
        props.getTraffic().setFallbackToDefault(true);
        props.getTraffic().setZone("zone-bj-1");
        props.getSecurity().setIpBlacklist(Arrays.asList("192.168.1.100", "10.0.0.0/8"));
        props.getSecurity().setIpWhitelist(Collections.singletonList("127.0.0.1"));
        props.getSign().setEnabled(true);
        props.getSign().setBodyEnabled(true);
        props.getSign().setIncludePaths(Collections.singletonList("/api/open/**"));

        // Nacos 动态路由仓库 (包含 2 条路由)
        NacosRouteDefinitionRepository nacosRepo = new NacosRouteDefinitionRepository(
                null, props.getDynamicRoute(), null, null);
        String nacosJson = """
                [
                    {"id": "nacos-route-1", "uri": "lb://user-service", "predicates": [{"name": "Path", "args": {"_genkey_0": "/api/user/**"}}]},
                    {"id": "nacos-route-2", "uri": "lb://order-service", "predicates": [{"name": "Path", "args": {"_genkey_0": "/api/order/**"}}]}
                ]
                """;
        assertTrue(nacosRepo.publishRoutes(nacosJson, false));

        // 主配置动态路由监听器 (包含 1 条基线路由)
        GatewayProperties gatewayProperties = new GatewayProperties();
        gatewayProperties.setRoutes(Collections.singletonList(
                createRoute("main-route-1", "forward:/mock/hello")));
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "my-test-gateway");
        GatewayRouteRefreshListener listener = new GatewayRouteRefreshListener(
                gatewayProperties, env, null, props.getDynamicRoute());
        listener.afterPropertiesSet();

        GatewayPanoramaEndpoint endpoint = new GatewayPanoramaEndpoint(
                providerOf(props),
                providerOf(nacosRepo),
                providerOf(listener),
                env
        );

        GatewayPanoramaReport report = endpoint.report();

        assertNotNull(report);
        assertEquals("UP", report.status());
        assertEquals("my-test-gateway", report.gatewayId());
        assertTrue(report.timestamp() > 0);

        // 1. 动态双轨路由检验
        GatewayPanoramaReport.DynamicRouteReport dynamicRoute = report.dynamicRoute();
        assertTrue(dynamicRoute.enabled());
        assertTrue(dynamicRoute.dualTrackEnabled());
        assertEquals(3, dynamicRoute.totalRouteCount(), "总路由数应为 Nacos(2) + 主配置(1) = 3");

        assertEquals(2, dynamicRoute.nacosRoute().routeCount());
        assertEquals("gateway-routes.json", dynamicRoute.nacosRoute().dataId());
        assertEquals("DEFAULT_GROUP", dynamicRoute.nacosRoute().group());
        assertTrue(dynamicRoute.nacosRoute().snapshotHealthy());
        assertTrue(dynamicRoute.nacosRoute().lastRefreshSuccess());
        assertNull(dynamicRoute.nacosRoute().lastRefreshError());

        assertEquals(1, dynamicRoute.mainConfigRoute().routeCount());
        assertTrue(dynamicRoute.mainConfigRoute().snapshotHealthy());
        assertTrue(dynamicRoute.mainConfigRoute().lastRefreshSuccess());
        assertNull(dynamicRoute.mainConfigRoute().lastRefreshError());

        // 2. 金丝雀灰度检验
        GatewayPanoramaReport.GrayLoadBalancerReport gray = report.grayLoadBalancer();
        assertTrue(gray.enabled());
        assertTrue(gray.fallbackToDefault());
        assertEquals("3s", gray.cacheTtl());
        assertEquals("zone-bj-1", gray.zone());

        // 3. 零信任边缘安全检验 (仅统计计数，零内网 IP 泄密)
        GatewayPanoramaReport.EdgeSecurityReport sec = report.edgeSecurity();
        assertTrue(sec.enabled());
        assertTrue(sec.saTokenEnabled());
        assertTrue(sec.pathValidationEnabled());
        assertEquals(2, sec.ipBlacklistCount(), "IP 黑名单应准确计数为 2 条");
        assertEquals(1, sec.ipWhitelistCount(), "IP 白名单应准确计数为 1 条");

        // 4. 开放接口签名检验
        GatewayPanoramaReport.SignGuardReport sign = report.signGuard();
        assertTrue(sign.enabled());
        assertTrue(sign.bodyEnabled());
        assertEquals("5m", sign.timestampThreshold());
        assertEquals(1, sign.includePathsCount());
    }

    @Test
    @DisplayName("测试当动态路由快照校验失败时状态降级为 DEGRADED 并输出因果异常")
    void testReportWithDegradedStatusWhenSnapshotFails() {
        AtengGatewayProperties props = new AtengGatewayProperties();
        NacosRouteDefinitionRepository nacosRepo = new NacosRouteDefinitionRepository(
                null, props.getDynamicRoute(), null, null);
        // 推送破损配置，触发快照守卫回退
        assertFalse(nacosRepo.publishRoutes("{ malformed json }", false));

        GatewayPanoramaEndpoint endpoint = new GatewayPanoramaEndpoint(
                providerOf(props),
                providerOf(nacosRepo),
                providerOf(null),
                new MockEnvironment()
        );

        GatewayPanoramaReport report = endpoint.report();
        assertNotNull(report);
        assertEquals("DEGRADED", report.status(), "快照回退或存在异常时状态应降级为 DEGRADED");
        assertFalse(report.dynamicRoute().nacosRoute().snapshotHealthy());
        assertFalse(report.dynamicRoute().nacosRoute().lastRefreshSuccess());
        assertNotNull(report.dynamicRoute().nacosRoute().lastRefreshError());
    }

    @Test
    @DisplayName("测试所有依赖 Provider 为空时安全保底返回默认 UP 报告")
    void testReportWithMissingProvidersGracefullyFallsBack() {
        GatewayPanoramaEndpoint endpoint = new GatewayPanoramaEndpoint(
                providerOf(null),
                providerOf(null),
                providerOf(null),
                null
        );

        GatewayPanoramaReport report = endpoint.report();
        assertNotNull(report);
        assertEquals("UP", report.status());
        assertEquals("ateng-gateway", report.gatewayId());
        assertEquals(0, report.dynamicRoute().totalRouteCount());
        assertFalse(report.dynamicRoute().dualTrackEnabled());
    }

    @Test
    @DisplayName("测试无锁内存快照微秒级极速响应")
    void testMicrosecondExecutionSpeed() {
        AtengGatewayProperties props = new AtengGatewayProperties();
        GatewayPanoramaEndpoint endpoint = new GatewayPanoramaEndpoint(
                providerOf(props),
                providerOf(null),
                providerOf(null),
                new MockEnvironment()
        );

        // 预热 JIT
        for (int i = 0; i < 500; i++) {
            endpoint.report();
        }

        long start = System.nanoTime();
        int iterations = 10_000;
        for (int i = 0; i < iterations; i++) {
            endpoint.report();
        }
        long elapsedNanos = System.nanoTime() - start;
        double avgMicros = (double) elapsedNanos / (iterations * 1_000);

        assertTrue(avgMicros < 100.0, "单次内存诊断报告耗时应在微秒级 (<100us), 实际为: " + avgMicros + "us");
    }
}
