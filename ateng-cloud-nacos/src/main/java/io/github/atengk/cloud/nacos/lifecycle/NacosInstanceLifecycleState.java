package io.github.atengk.cloud.nacos.lifecycle;

/**
 * Nacos 微服务节点生命周期状态枚举
 *
 * @author Ateng
 * @since 2026-10-27
 */
public enum NacosInstanceLifecycleState {

    /**
     * 在线正常运行状态（对外接入流量）
     */
    UP,

    /**
     * 正在排空下线中（已注销 Nacos 实例，Readiness 探针进入 REFUSING_TRAFFIC，正在安全排空窗口内等待）
     */
    DRAINING,

    /**
     * 已下线 / 处于停机排空就绪状态（停止接收新流量）
     */
    OUT_OF_SERVICE
}
