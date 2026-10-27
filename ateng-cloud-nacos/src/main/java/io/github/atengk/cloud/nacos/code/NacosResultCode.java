package io.github.atengk.cloud.nacos.code;

import io.github.atengk.boot.core.code.IResultCode;

/**
 * Nacos 注册与配置中心专用业务错误码
 *
 * <p>编码段规划遵循 ateng-boot-core ResultCodeSegment.NETWORK_RPC 规范 (4000-4999)。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public enum NacosResultCode implements IResultCode {

    /**
     * Nacos 客户端通信异常
     */
    NACOS_CLIENT_ERROR(4101, "Nacos 客户端通信异常"),

    /**
     * Nacos 服务实例不存在
     */
    NACOS_SERVICE_NOT_FOUND(4102, "Nacos 服务实例不存在"),

    /**
     * Nacos 运维操作执行失败
     */
    NACOS_OPERATION_FAILED(4103, "Nacos 运维操作执行失败"),

    /**
     * Nacos 服务注册与发现未启用
     */
    NACOS_DISCOVERY_DISABLED(4104, "Nacos 服务注册与发现未启用"),

    /**
     * Nacos 配置中心运维操作执行失败
     */
    NACOS_CONFIG_OPERATION_FAILED(4105, "Nacos 配置中心运维操作执行失败"),

    /**
     * Nacos 动态配置中心未启用
     */
    NACOS_CONFIG_DISABLED(4106, "Nacos 动态配置中心未启用"),

    /**
     * Nacos 配置解析失败
     */
    NACOS_CONFIG_PARSE_FAILED(4107, "Nacos 配置解析失败"),

    /**
     * Nacos 配置语法校验失败
     */
    NACOS_CONFIG_VALIDATION_FAILED(4108, "Nacos 配置语法校验失败");

    private final Integer code;
    private final String msg;

    NacosResultCode(Integer code, String msg) {
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
