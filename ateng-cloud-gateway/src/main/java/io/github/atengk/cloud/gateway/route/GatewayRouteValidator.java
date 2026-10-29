package io.github.atengk.cloud.gateway.route;

import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 网关动态路由定义深度语法与语义校验器
 *
 * <p>为独立路由文件 (JSON) 与微服务主配置 (spring.cloud.gateway.routes) 提供统一的严格语义校验防线：
 * <ul>
 *     <li>检查路由 ID 非空且在当前集合内全局唯一；</li>
 *     <li>检查路由目标 URI 非空且具备合法协议头 (scheme，如 lb/http/https)；</li>
 *     <li>检查谓词定义 (PredicateDefinition) 名称非空；</li>
 *     <li>检查过滤器定义 (FilterDefinition) 名称非空。</li>
 * </ul>
 * 校验失败直接抛出 {@link IllegalArgumentException}，触发快照守卫回退，坚决杜绝脏配置打崩内存路由表。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public final class GatewayRouteValidator {

    private GatewayRouteValidator() {
        // 工具类禁止实例化
    }

    /**
     * 对动态路由列表执行深度语法与语义防卫校验
     *
     * @param routeDefinitions 路由定义列表
     * @throws IllegalArgumentException 当任何一条路由定义不满足语法或语义约束时抛出
     */
    public static void validateRouteDefinitions(List<RouteDefinition> routeDefinitions) {
        if (routeDefinitions == null || routeDefinitions.isEmpty()) {
            return;
        }
        Set<String> routeIds = new HashSet<>();
        for (RouteDefinition route : routeDefinitions) {
            if (route == null) {
                throw new IllegalArgumentException("RouteDefinition 不能为 null");
            }
            if (!StringUtils.hasText(route.getId())) {
                throw new IllegalArgumentException("RouteDefinition ID 不能为空");
            }
            if (!routeIds.add(route.getId())) {
                throw new IllegalArgumentException("存在重复的 RouteDefinition ID: " + route.getId());
            }
            URI uri = route.getUri();
            if (uri == null) {
                throw new IllegalArgumentException("RouteDefinition [" + route.getId() + "] URI 不能为空");
            }
            String scheme = uri.getScheme();
            if (!StringUtils.hasText(scheme)) {
                throw new IllegalArgumentException("RouteDefinition [" + route.getId() + "] URI 协议头 (scheme) 不能为空: " + uri);
            }

            // 校验断言
            if (route.getPredicates() != null) {
                for (PredicateDefinition predicate : route.getPredicates()) {
                    if (predicate == null || !StringUtils.hasText(predicate.getName())) {
                        throw new IllegalArgumentException("RouteDefinition [" + route.getId() + "] 包含无效 PredicateDefinition (名称不能为空)");
                    }
                }
            }

            // 校验过滤器
            if (route.getFilters() != null) {
                for (FilterDefinition filter : route.getFilters()) {
                    if (filter == null || !StringUtils.hasText(filter.getName())) {
                        throw new IllegalArgumentException("RouteDefinition [" + route.getId() + "] 包含无效 FilterDefinition (名称不能为空)");
                    }
                }
            }
        }
    }

    /**
     * 对单条动态路由执行深度语法与语义防卫校验
     *
     * @param route 路由定义
     * @throws IllegalArgumentException 当路由定义不满足语法或语义约束时抛出
     */
    public static void validateRouteDefinition(RouteDefinition route) {
        if (route == null) {
            throw new IllegalArgumentException("RouteDefinition 不能为 null");
        }
        validateRouteDefinitions(Collections.singletonList(route));
    }
}
