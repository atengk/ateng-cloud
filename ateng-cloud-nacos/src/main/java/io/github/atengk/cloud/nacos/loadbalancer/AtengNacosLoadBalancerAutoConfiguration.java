package io.github.atengk.cloud.nacos.loadbalancer;

import io.github.atengk.cloud.nacos.config.AtengNacosAutoConfiguration;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;

/**
 * Nacos 客户端负载均衡治理增强自动装配
 *
 * <p>当类路径存在 Spring Cloud LoadBalancer 且 {@code ateng.cloud.nacos.loadbalancer.enabled=true} 时激活，
 * 默认向全量微服务客户端注入同机房就近亲和与启动预热加权调度支持。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@AutoConfiguration(after = AtengNacosAutoConfiguration.class)
@ConditionalOnClass({ServiceInstanceListSupplier.class, LoadBalancerClientFactory.class})
@ConditionalOnProperty(prefix = "ateng.cloud.nacos.loadbalancer", name = "enabled", havingValue = "true", matchIfMissing = true)
@LoadBalancerClients(defaultConfiguration = AtengNacosLoadBalancerClientConfiguration.class)
public class AtengNacosLoadBalancerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengNacosLoadBalancerAutoConfiguration.class);

    public AtengNacosLoadBalancerAutoConfiguration() {
        log.info("[ateng-cloud-nacos] Nacos client loadbalancer autoconfiguration activated (Zone Affinity & Startup Preheating enabled).");
    }

    /**
     * 注册节点自适应系统资源（CPU/内存负载）平滑加权计算器
     *
     * @param properties Nacos 配置属性
     * @return AdaptiveInstanceWeightCalculator
     */
    @Bean
    @ConditionalOnMissingBean(InstanceWeightCalculator.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.nacos.loadbalancer.adaptive-weight", name = "enabled", havingValue = "true")
    public InstanceWeightCalculator adaptiveInstanceWeightCalculator(AtengNacosProperties properties) {
        return new AdaptiveInstanceWeightCalculator(properties);
    }
}
