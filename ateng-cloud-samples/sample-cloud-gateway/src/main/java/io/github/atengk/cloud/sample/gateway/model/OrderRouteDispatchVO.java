package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;

/**
 * 电商订单路由分发与金丝雀灰度分流视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public record OrderRouteDispatchVO(
        String orderId,
        String serviceId,
        String trafficTag,
        String targetZone,
        String traceId,
        String clientIp,
        String status,
        boolean fallbackToBaseline
) implements Serializable {
}
