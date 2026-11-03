package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;

/**
 * 动态路由热更与快照守卫执行结果视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public record RouteRefreshResultVO(
        String status,
        int activeRouteCount,
        String snapshotStatus,
        String message
) implements Serializable {
}
