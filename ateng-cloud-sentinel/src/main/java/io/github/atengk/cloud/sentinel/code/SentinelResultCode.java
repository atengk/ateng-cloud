package io.github.atengk.cloud.sentinel.code;

import io.github.atengk.boot.core.code.IResultCode;

/**
 * Sentinel 流量防卫与熔断降级专用业务错误码
 *
 * <p>编码段规划遵循 ateng-boot-core ResultCodeSegment.NETWORK_RPC 规范 (4200-4299)。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public enum SentinelResultCode implements IResultCode {

    /**
     * 服务流量过载，触发限流保护
     */
    FLOW_LIMIT(4201, "服务流量过载，触发限流保护"),

    /**
     * 服务暂时不可用，触发降级熔断
     */
    DEGRADE(4202, "服务暂时不可用，触发降级熔断"),

    /**
     * 热点参数访问频繁，触发限流保护
     */
    PARAM_FLOW_LIMIT(4203, "热点参数访问频繁，触发限流保护"),

    /**
     * 系统负载过高，触发保护机制
     */
    SYSTEM_BLOCK(4204, "系统负载过高，触发保护机制"),

    /**
     * 访问源未授权，已被规则拦截
     */
    AUTHORITY_BLOCK(4205, "访问源未授权，已被规则拦截");

    private final Integer code;
    private final String msg;

    SentinelResultCode(Integer code, String msg) {
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
