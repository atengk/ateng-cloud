package io.github.atengk.cloud.sample.nacos.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator;
import io.github.atengk.cloud.sample.nacos.model.InstanceVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Spring Cloud LoadBalancer 负载均衡亲和调度与自适应动态加权控制器
 *
 * <p>显式遵循强类型 {@link Result} 契约，演示集成同机房（Zone Affinity）优先路由、
 * 60s 冷启动加权平滑预热（Startup Preheating）以及下游节点系统负载自适应调谐调度与阻尼防抖。</p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/nacos/loadbalancer")
public class NacosLoadBalancerController {

    private final ObjectProvider<LoadBalancerClient> loadBalancerClientProvider;
    private final ObjectProvider<InstanceWeightCalculator> instanceWeightCalculatorProvider;

    public NacosLoadBalancerController(ObjectProvider<LoadBalancerClient> loadBalancerClientProvider,
                                       ObjectProvider<InstanceWeightCalculator> instanceWeightCalculatorProvider) {
        this.loadBalancerClientProvider = loadBalancerClientProvider;
        this.instanceWeightCalculatorProvider = instanceWeightCalculatorProvider;
    }

    /**
     * 根据客户端负载均衡器挑选最优服务实例
     *
     * <p>验证同机房可用区（Zone Affinity）优先路由与 60s 冷启动加权预热综合调度。</p>
     *
     * @param serviceName 目标微服务名称，默认为 sample-cloud-nacos
     * @return 选举出的最优服务实例节点快照
     */
    @GetMapping("/choose")
    public Result<InstanceVO> chooseInstance(@RequestParam(defaultValue = "sample-cloud-nacos") String serviceName) {
        LoadBalancerClient loadBalancerClient = loadBalancerClientProvider.getIfAvailable();
        if (loadBalancerClient == null) {
            return Result.success(null);
        }
        ServiceInstance chosen = loadBalancerClient.choose(serviceName);
        return Result.success(chosen != null ? InstanceVO.fromServiceInstance(chosen) : null);
    }

    /**
     * 演示基于系统负载指标（CPU/内存）的自适应动态加权调谐与阻尼防抖
     *
     * @param currentWeight 实例当前基准权重
     * @param cpuUsage 当前 CPU 利用率（0.0 ~ 1.0）
     * @param instanceId 模拟实例 ID
     * @param port 模拟实例端口
     * @return 计算后的有效权重（过载时平滑降权，全集群过载时兜底防雪崩）
     */
    @GetMapping("/calculate-weight")
    public Result<Double> calculateWeight(
            @RequestParam(defaultValue = "1.0") double currentWeight,
            @RequestParam(defaultValue = "0.90") double cpuUsage,
            @RequestParam(defaultValue = "demo-inst-1") String instanceId,
            @RequestParam(defaultValue = "8080") int port) {
        InstanceWeightCalculator calculator = instanceWeightCalculatorProvider.getIfAvailable();
        if (calculator == null) {
            return Result.success(currentWeight);
        }
        DefaultServiceInstance instance = new DefaultServiceInstance(
                instanceId,
                "sample-cloud-nacos",
                "127.0.0.1",
                port,
                false,
                Map.of("cpu-usage", String.valueOf(cpuUsage), "nacos.weight", "1.0")
        );
        double calculated = calculator.calculateWeight(instance, currentWeight);
        return Result.success(calculated);
    }
}
