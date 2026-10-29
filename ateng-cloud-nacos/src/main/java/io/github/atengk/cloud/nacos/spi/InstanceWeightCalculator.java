package io.github.atengk.cloud.nacos.spi;

import org.springframework.cloud.client.ServiceInstance;

/**
 * Nacos 候选实例动态权重计算器 SPI 扩展点
 *
 * <p>允许业务或治理框架介入对候选微服务实例的有效权重（Effective Weight）计算，
 * 支持基于节点系统负载（CPU/内存使用率）、响应延迟（RTT）或业务自定义健康分进行动态加权或平滑降权。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@FunctionalInterface
public interface InstanceWeightCalculator {

    /**
     * 计算并调谐候选服务实例的最终有效权重
     *
     * @param instance      目标微服务实例（携带 host, port, metadata 等元数据）
     * @param currentWeight 当前计算出的基础权重（通常已融合 nacos.weight 静态权重与 JVM 启动平滑预热升权系数）
     * @return 调谐后的最终生效权重（返回 &lt;= 0.0 表示摘流/不分配流量，1.0 为基准权重，&gt; 1.0 表示加权倾斜）
     */
    double calculateWeight(ServiceInstance instance, double currentWeight);

    /**
     * 判断目标微服务实例当前是否处于严重过载状态
     *
     * <p>用于配合全集群过载熔断防线：当检测到候选池中所有节点均处于过载状态时，
     * 负载均衡选择器自动安全降级为均匀轮询，确保可用性绝对优先。</p>
     *
     * @param instance 目标微服务实例
     * @return true 表示该实例当前处于严重过载状态；默认返回 false
     */
    default boolean isInstanceOverloaded(ServiceInstance instance) {
        return false;
    }
}
