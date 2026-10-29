package io.github.atengk.cloud.nacos.loadbalancer;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplierBuilder;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Nacos 客户端负载均衡每服务客户端上下文配置
 *
 * <p>为每个微服务客户端子上下文注入同机房亲和提供者与启动预热加权选择器。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengNacosLoadBalancerClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengNacosLoadBalancerClientConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.cloud.nacos.loadbalancer", name = "client-supplier-enabled", havingValue = "true", matchIfMissing = true)
    public ServiceInstanceListSupplier nacosServiceInstanceListSupplier(
            ConfigurableApplicationContext context,
            AtengNacosProperties properties) {
        ServiceInstanceListSupplierBuilder builder = ServiceInstanceListSupplier.builder();
        if (hasReactiveDiscoveryClient(context)) {
            builder.withDiscoveryClient();
        } else {
            builder.withBlockingDiscoveryClient();
        }
        ServiceInstanceListSupplier baseSupplier = builder
                .withCaching()
                .build(context);
        return new ZonePreferenceServiceInstanceListSupplier(baseSupplier, properties, context.getEnvironment());
    }

    private boolean hasReactiveDiscoveryClient(ConfigurableApplicationContext context) {
        try {
            return context.getBeanProvider(ReactiveDiscoveryClient.class).getIfAvailable() != null;
        } catch (Throwable ex) {
            log.trace("[ateng-cloud-nacos] Probing ReactiveDiscoveryClient failed, falling back to false: {}", ex.getMessage());
            return false;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactorLoadBalancer<ServiceInstance> nacosWarmupLoadBalancer(
            Environment environment,
            LoadBalancerClientFactory loadBalancerClientFactory,
            AtengNacosProperties properties,
            ObjectProvider<InstanceWeightCalculator> weightCalculatorProvider) {
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        return new NacosWarmupLoadBalancer(name,
                loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class),
                properties,
                weightCalculatorProvider);
    }
}
