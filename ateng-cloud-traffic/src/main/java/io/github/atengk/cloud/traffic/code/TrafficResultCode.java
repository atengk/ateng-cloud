package io.github.atengk.cloud.traffic.code;

import io.github.atengk.boot.core.code.IResultCode;

/**
 * 全链路流量治理与灰度路由专用业务错误码
 *
 * <p>编码段规划遵循 ateng-boot-core ResultCodeSegment.NETWORK_RPC 规范 (4200-4299)。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public enum TrafficResultCode implements IResultCode {

    /**
     * 严格泳道隔离阻断：目标服务无匹配灰度可用实例
     */
    STRICT_LANE_BLOCK(4210, "严格泳道隔离阻断：目标服务无匹配的灰度可用实例"),

    /**
     * 流量染色与灰度调度异常
     */
    TRAFFIC_ROUTING_ERROR(4211, "流量染色与灰度调度异常");

    private final Integer code;
    private final String msg;

    TrafficResultCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public Integer getCode() {
        return this.code;
    }

    @Override
    public String getMsg() {
        return this.msg;
    }
}
