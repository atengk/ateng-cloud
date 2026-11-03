package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;
import java.util.List;

/**
 * 响应式网关路由定义契约视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public record RouteDefinitionVO(
        String routeId,
        String uri,
        List<String> predicates,
        List<String> filters,
        int order
) implements Serializable {
}
