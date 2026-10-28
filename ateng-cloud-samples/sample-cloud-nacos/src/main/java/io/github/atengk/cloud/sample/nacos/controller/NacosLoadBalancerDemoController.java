package io.github.atengk.cloud.sample.nacos.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.nacos.model.InstanceVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring Cloud LoadBalancer 负载均衡亲和调度演示控制器
 *
 * <p>演示集成同机房（Zone Affinity）优先路由与冷启动加权预热（Startup Preheating）调度。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/api/nacos/loadbalancer")
public class NacosLoadBalancerDemoController {

    private final ObjectProvider<LoadBalancerClient> loadBalancerClientProvider;

    public NacosLoadBalancerDemoController(ObjectProvider<LoadBalancerClient> loadBalancerClientProvider) {
        this.loadBalancerClientProvider = loadBalancerClientProvider;
    }

    /**
     * 根据负载均衡选择器挑选最优服务实例
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
}
