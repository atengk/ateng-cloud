package io.github.atengk.cloud.gateway.route;

import io.github.atengk.cloud.gateway.properties.AtengGatewayDynamicRouteProperties;
import io.github.atengk.cloud.nacos.event.SafeConfigRefreshEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微服务主配置文件 ({@code spring.cloud.gateway.routes}) 动态感知与快照守卫黑盒测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class GatewayRouteRefreshListenerTest {

    private GatewayProperties gatewayProperties;
    private MockEnvironment environment;
    private AtengGatewayDynamicRouteProperties dynamicRouteProperties;
    private List<ApplicationEvent> publishedEvents;
    private ApplicationEventPublisher eventPublisher;
    private GatewayRouteRefreshListener refreshListener;

    @BeforeEach
    void setUp() {
        gatewayProperties = new GatewayProperties();
        environment = new MockEnvironment();
        dynamicRouteProperties = new AtengGatewayDynamicRouteProperties();
        dynamicRouteProperties.setEnabled(true);
        publishedEvents = new ArrayList<>();
        eventPublisher = event -> {
            if (event instanceof ApplicationEvent appEvent) {
                publishedEvents.add(appEvent);
            }
        };

        refreshListener = new GatewayRouteRefreshListener(
                gatewayProperties, environment, eventPublisher, dynamicRouteProperties);
    }

    @Test
    @DisplayName("测试主配置路由正常热更新并发布 RefreshRoutesEvent")
    void testNormalRouteUpdateFromEnvironment() {
        // 初始无路由
        assertEquals(0, gatewayProperties.getRoutes().size());

        // 模拟外部主配置向 Spring Environment 注入新路由配置
        environment.setProperty("spring.cloud.gateway.routes[0].id", "user-service-route");
        environment.setProperty("spring.cloud.gateway.routes[0].uri", "lb://user-service");
        environment.setProperty("spring.cloud.gateway.routes[0].predicates[0].name", "Path");
        environment.setProperty("spring.cloud.gateway.routes[0].predicates[0].args._genkey_0", "/api/user/**");

        // 触发 EnvironmentChangeEvent
        EnvironmentChangeEvent event = new EnvironmentChangeEvent(
                Set.of("spring.cloud.gateway.routes[0].id", "spring.cloud.gateway.routes[0].uri"));
        refreshListener.onApplicationEvent(event);

        // 验证路由生效且发布了 RefreshRoutesEvent
        assertEquals(1, gatewayProperties.getRoutes().size());
        RouteDefinition updatedRoute = gatewayProperties.getRoutes().get(0);
        assertEquals("user-service-route", updatedRoute.getId());
        assertEquals(URI.create("lb://user-service"), updatedRoute.getUri());
        assertEquals(1, publishedEvents.size());
        assertTrue(publishedEvents.get(0) instanceof RefreshRoutesEvent);
    }

    @Test
    @DisplayName("测试无关属性变更被快速忽略且不发布事件")
    void testIrrelevantEnvironmentChangeIgnored() {
        environment.setProperty("server.port", "8081");
        environment.setProperty("spring.application.name", "gateway-demo");

        EnvironmentChangeEvent event = new EnvironmentChangeEvent(
                Set.of("server.port", "spring.application.name"));
        boolean refreshed = refreshListener.refreshRoutesFromEnvironment(event.getKeys());

        assertFalse(refreshed);
        assertEquals(0, publishedEvents.size());
        assertEquals(0, gatewayProperties.getRoutes().size());
    }

    @Test
    @DisplayName("测试启动时初始配置即破损时不污染快照基线")
    void testCorruptInitialRoutesDoesNotPoisonSnapshot() {
        RouteDefinition badRoute = new RouteDefinition();
        badRoute.setId("corrupt-initial");
        badRoute.setUri(URI.create("/no-scheme"));
        gatewayProperties.setRoutes(Collections.singletonList(badRoute));

        refreshListener.afterPropertiesSet();

        assertEquals(0, refreshListener.getLastKnownGoodRoutes().size(), "初始破损路由严禁污染有效快照基线");
    }

    @Test
    @DisplayName("测试 default-filters 全局默认过滤器变更触发路由刷新")
    void testDefaultFiltersUpdate() {
        environment.setProperty("spring.cloud.gateway.default-filters[0].name", "AddResponseHeader");
        environment.setProperty("spring.cloud.gateway.default-filters[0].args._genkey_0", "X-Custom-Header");
        environment.setProperty("spring.cloud.gateway.default-filters[0].args._genkey_1", "CustomValue");

        boolean refreshed = refreshListener.refreshRoutesFromEnvironment(
                Set.of("spring.cloud.gateway.default-filters[0].name"));

        assertTrue(refreshed);
        assertEquals(1, publishedEvents.size());
        assertEquals(1, gatewayProperties.getDefaultFilters().size());
        assertEquals("AddResponseHeader", gatewayProperties.getDefaultFilters().get(0).getName());
    }

    @Test
    @DisplayName("测试快照守卫容错：主配置注入非法路由时坚决回滚至有效快照且不发布事件")
    void testSnapshotGuardOnMalformedRoute() {
        // 1. 初始化有效路由快照
        RouteDefinition goodRoute = new RouteDefinition();
        goodRoute.setId("good-order-route");
        goodRoute.setUri(URI.create("lb://order-service"));
        goodRoute.setPredicates(Collections.singletonList(new PredicateDefinition("Path=/api/order/**")));
        gatewayProperties.setRoutes(Collections.singletonList(goodRoute));
        refreshListener.afterPropertiesSet();

        assertEquals(1, refreshListener.getLastKnownGoodRoutes().size());
        assertEquals("good-order-route", refreshListener.getLastKnownGoodRoutes().get(0).getId());

        // 2. 模拟外部配置中心推送了非法的路由 (缺少 URI 协议头 scheme)
        environment.setProperty("spring.cloud.gateway.routes[0].id", "malformed-route");
        environment.setProperty("spring.cloud.gateway.routes[0].uri", "/no-scheme-uri");

        EnvironmentChangeEvent event = new EnvironmentChangeEvent(
                Set.of("spring.cloud.gateway.routes[0].id", "spring.cloud.gateway.routes[0].uri"));
        boolean refreshed = refreshListener.refreshRoutesFromEnvironment(event.getKeys());

        // 验证刷新被拦截回退
        assertFalse(refreshed);
        assertEquals(0, publishedEvents.size(), "破损路由严禁发布 RefreshRoutesEvent");

        // 验证受管 GatewayProperties 依然完整保留上一份有效快照，未被脏数据污染
        assertEquals(1, gatewayProperties.getRoutes().size());
        assertEquals("good-order-route", gatewayProperties.getRoutes().get(0).getId());
        assertEquals(1, refreshListener.getLastKnownGoodRoutes().size());
    }

    @Test
    @DisplayName("测试 SafeConfigRefreshEvent 事件能够无缝驱动路由热更")
    void testSafeConfigRefreshEventListener() {
        environment.setProperty("spring.cloud.gateway.routes[0].id", "payment-route");
        environment.setProperty("spring.cloud.gateway.routes[0].uri", "lb://payment-service");
        environment.setProperty("spring.cloud.gateway.routes[0].predicates[0].name", "Path");
        environment.setProperty("spring.cloud.gateway.routes[0].predicates[0].args._genkey_0", "/api/payment/**");

        SafeConfigRefreshEvent safeEvent = new SafeConfigRefreshEvent(
                this,
                "sample-cloud-gateway.yaml",
                "DEFAULT_GROUP",
                Set.of("spring.cloud.gateway.routes[0].id", "spring.cloud.gateway.routes[0].uri"),
                Collections.emptyMap(),
                "trace-12345"
        );

        refreshListener.onApplicationEvent(safeEvent);

        assertEquals(1, gatewayProperties.getRoutes().size());
        assertEquals("payment-route", gatewayProperties.getRoutes().get(0).getId());
        assertEquals(1, publishedEvents.size());
        assertTrue(publishedEvents.get(0) instanceof RefreshRoutesEvent);
    }

    @Test
    @DisplayName("测试路由指纹幂等去重：相同路由配置重复触发时不重复发布刷新事件")
    void testIdempotentDeduplication() {
        environment.setProperty("spring.cloud.gateway.routes[0].id", "account-route");
        environment.setProperty("spring.cloud.gateway.routes[0].uri", "lb://account-service");

        // 第一次热更
        refreshListener.refreshRoutesFromEnvironment(Set.of("spring.cloud.gateway.routes[0].id"));
        assertEquals(1, publishedEvents.size());

        // 第二次接收完全相同的环境变更
        boolean result = refreshListener.refreshRoutesFromEnvironment(Set.of("spring.cloud.gateway.routes[0].id"));
        assertTrue(result);
        assertEquals(1, publishedEvents.size(), "内容无变化时不应重复发布 RefreshRoutesEvent");
    }

    @Test
    @DisplayName("测试在主配置中清空所有路由时安全清空并发布刷新事件")
    void testRouteClearing() {
        // 先初始化一条有效路由
        environment.setProperty("spring.cloud.gateway.routes[0].id", "initial-route");
        environment.setProperty("spring.cloud.gateway.routes[0].uri", "lb://initial-service");
        refreshListener.refreshRoutesFromEnvironment(Set.of("spring.cloud.gateway.routes[0].id"));
        assertEquals(1, gatewayProperties.getRoutes().size());
        assertEquals(1, publishedEvents.size());

        // 模拟在配置中移除了所有路由配置
        MockEnvironment emptyEnv = new MockEnvironment();
        GatewayRouteRefreshListener listenerWithEmptyEnv = new GatewayRouteRefreshListener(
                gatewayProperties, emptyEnv, eventPublisher, dynamicRouteProperties);

        listenerWithEmptyEnv.refreshRoutesFromEnvironment(Set.of("spring.cloud.gateway.routes"));

        assertEquals(0, gatewayProperties.getRoutes().size());
        assertEquals(2, publishedEvents.size());
    }

    @Test
    @DisplayName("测试动态路由配置关闭时跳过主配置热更")
    void testDisabledConfiguration() {
        dynamicRouteProperties.setEnabled(false);
        environment.setProperty("spring.cloud.gateway.routes[0].id", "disabled-route");
        environment.setProperty("spring.cloud.gateway.routes[0].uri", "lb://disabled-service");

        boolean result = refreshListener.refreshRoutesFromEnvironment(Set.of("spring.cloud.gateway.routes[0].id"));
        assertFalse(result);
        assertEquals(0, publishedEvents.size());
        assertEquals(0, gatewayProperties.getRoutes().size());
    }

    @Test
    @DisplayName("测试 isRouteRelatedKey 键名前缀判定逻辑")
    void testIsRouteRelatedKey() {
        assertTrue(GatewayRouteRefreshListener.isRouteRelatedKey("spring.cloud.gateway.routes"));
        assertTrue(GatewayRouteRefreshListener.isRouteRelatedKey("spring.cloud.gateway.routes[0].id"));
        assertTrue(GatewayRouteRefreshListener.isRouteRelatedKey("spring.cloud.gateway.routes[1].uri"));
        assertTrue(GatewayRouteRefreshListener.isRouteRelatedKey("spring.cloud.gateway.default-filters"));
        assertTrue(GatewayRouteRefreshListener.isRouteRelatedKey("spring.cloud.gateway.default-filters[0].name"));
        assertTrue(GatewayRouteRefreshListener.isRouteRelatedKey("spring.cloud.gateway.defaultfilters[0].name"));

        assertFalse(GatewayRouteRefreshListener.isRouteRelatedKey(null));
        assertFalse(GatewayRouteRefreshListener.isRouteRelatedKey(""));
        assertFalse(GatewayRouteRefreshListener.isRouteRelatedKey("spring.cloud.gateway.enabled"));
        assertFalse(GatewayRouteRefreshListener.isRouteRelatedKey("server.port"));
        assertFalse(GatewayRouteRefreshListener.isRouteRelatedKey("ateng.cloud.gateway.dynamic-route.enabled"));
    }
}
