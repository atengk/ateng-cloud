package io.github.atengk.cloud.gateway.route;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网关动态路由深度语法与语义校验器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class GatewayRouteValidatorTest {

    @Test
    @DisplayName("测试空列表或 null 输入安全跳过")
    void testEmptyOrNullListPasses() {
        assertDoesNotThrow(() -> GatewayRouteValidator.validateRouteDefinitions(null));
        assertDoesNotThrow(() -> GatewayRouteValidator.validateRouteDefinitions(Collections.emptyList()));
    }

    @Test
    @DisplayName("测试单条合法路由校验通过")
    void testValidRoutePasses() {
        RouteDefinition route = new RouteDefinition();
        route.setId("valid-route");
        route.setUri(URI.create("lb://user-service"));

        PredicateDefinition predicate = new PredicateDefinition("Path=/api/user/**");
        route.setPredicates(Collections.singletonList(predicate));

        FilterDefinition filter = new FilterDefinition("StripPrefix=1");
        route.setFilters(Collections.singletonList(filter));

        assertDoesNotThrow(() -> GatewayRouteValidator.validateRouteDefinition(route));
        assertDoesNotThrow(() -> GatewayRouteValidator.validateRouteDefinitions(Collections.singletonList(route)));
    }

    @Test
    @DisplayName("测试路由为 null 时快速失败")
    void testNullRouteThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GatewayRouteValidator.validateRouteDefinition(null));
        assertTrue(ex.getMessage().contains("不能为 null"));
    }

    @Test
    @DisplayName("测试路由 ID 为空时快速失败")
    void testEmptyRouteIdThrows() {
        RouteDefinition route = new RouteDefinition();
        route.setId("   ");
        route.setUri(URI.create("lb://test-service"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GatewayRouteValidator.validateRouteDefinition(route));
        assertTrue(ex.getMessage().contains("ID 不能为空"));
    }

    @Test
    @DisplayName("测试重复路由 ID 时快速失败")
    void testDuplicateRouteIdThrows() {
        RouteDefinition r1 = new RouteDefinition();
        r1.setId("duplicate-id");
        r1.setUri(URI.create("lb://service-a"));

        RouteDefinition r2 = new RouteDefinition();
        r2.setId("duplicate-id");
        r2.setUri(URI.create("lb://service-b"));

        List<RouteDefinition> list = List.of(r1, r2);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GatewayRouteValidator.validateRouteDefinitions(list));
        assertTrue(ex.getMessage().contains("存在重复的 RouteDefinition ID"));
    }

    @Test
    @DisplayName("测试路由 URI 为 null 时快速失败")
    void testNullUriThrows() {
        RouteDefinition route = new RouteDefinition();
        route.setId("no-uri-route");
        route.setUri(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GatewayRouteValidator.validateRouteDefinition(route));
        assertTrue(ex.getMessage().contains("URI 不能为空"));
    }

    @Test
    @DisplayName("测试路由 URI 缺少 scheme 时快速失败")
    void testUriWithoutSchemeThrows() {
        RouteDefinition route = new RouteDefinition();
        route.setId("no-scheme-route");
        route.setUri(URI.create("/local/path/without/scheme"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GatewayRouteValidator.validateRouteDefinition(route));
        assertTrue(ex.getMessage().contains("协议头 (scheme) 不能为空"));
    }

    @Test
    @DisplayName("测试断言名称为空时快速失败")
    void testEmptyPredicateNameThrows() {
        RouteDefinition route = new RouteDefinition();
        route.setId("bad-predicate-route");
        route.setUri(URI.create("lb://test-service"));

        PredicateDefinition predicate = new PredicateDefinition();
        predicate.setName("  ");
        route.setPredicates(Collections.singletonList(predicate));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GatewayRouteValidator.validateRouteDefinition(route));
        assertTrue(ex.getMessage().contains("PredicateDefinition (名称不能为空)"));
    }

    @Test
    @DisplayName("测试过滤器名称为空时快速失败")
    void testEmptyFilterNameThrows() {
        RouteDefinition route = new RouteDefinition();
        route.setId("bad-filter-route");
        route.setUri(URI.create("lb://test-service"));

        FilterDefinition filter = new FilterDefinition();
        filter.setName("");
        route.setFilters(Collections.singletonList(filter));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GatewayRouteValidator.validateRouteDefinition(route));
        assertTrue(ex.getMessage().contains("FilterDefinition (名称不能为空)"));
    }
}
