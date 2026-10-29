package io.github.atengk.cloud.traffic.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.traffic.code.TrafficResultCode;

/**
 * 流量灰度路由与泳道调度业务异常
 *
 * <p>在金融级严格隔离模式（{@code strictLane=true}）下，当未匹配到对应灰度实例时抛出，实现即时 Fail-Fast 阻断。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficRoutingException extends BusinessException {

    private final String serviceId;
    private final String tag;

    public TrafficRoutingException(String serviceId, String tag) {
        this(serviceId, tag, null);
    }

    public TrafficRoutingException(String serviceId, String tag, Throwable cause) {
        super(TrafficResultCode.STRICT_LANE_BLOCK,
                String.format("严格泳道隔离阻断: 目标服务 '%s' 无匹配灰度标签 '%s' 的可用实例 (strict-lane enabled)", serviceId, tag),
                cause);
        this.serviceId = serviceId;
        this.tag = tag;
    }

    public TrafficRoutingException(IResultCode resultCode, String serviceId, String tag, String message) {
        this(resultCode, serviceId, tag, message, null);
    }

    public TrafficRoutingException(IResultCode resultCode, String serviceId, String tag, String message, Throwable cause) {
        super(resultCode, message, cause);
        this.serviceId = serviceId;
        this.tag = tag;
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getTag() {
        return tag;
    }
}
