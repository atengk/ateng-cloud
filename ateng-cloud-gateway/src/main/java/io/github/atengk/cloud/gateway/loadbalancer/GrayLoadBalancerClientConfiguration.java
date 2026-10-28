package io.github.atengk.cloud.gateway.loadbalancer;

import io.github.atengk.cloud.gateway.properties.AtengGatewayProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.loadbalancer.cache.LoadBalancerCacheProperties;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 响应式网关金丝雀灰度与可用区就近负载均衡器客户端配置类
 *
 * <p>核心治理语义：
 * 1. 装配链严格按 DiscoveryClient → Gray → ZonePreference → Caching 顺序嵌套；
 * 2. 默认将网关实例缓存 TTL 压缩至 3s，敏捷感知动态灰度及上下线变更。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Configuration(proxyBeanMethods = false)
public class GrayLoadBalancerClientConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ServiceInstanceListSupplier serviceInstanceListSupplier(
            ConfigurableApplicationContext context,
            ObjectProvider<AtengGatewayProperties> propertiesProvider) {
        AtengGatewayTrafficProperties trafficProperties = propertiesProvider.getIfAvailable() != null
                ? propertiesProvider.getIfAvailable().getTraffic()
                : new AtengGatewayTrafficProperties();

        return ServiceInstanceListSupplier.builder()
                .withDiscoveryClient()
                .with((ctx, delegate) -> new GrayServiceInstanceListSupplier(delegate, trafficProperties))
                .withZonePreference()
                .withCaching()
                .build(context);
    }

    /**
     * 注册负载均衡本地缓存属性，默认将实例缓存 TTL 压缩至 3s
     */
    @Bean
    @ConditionalOnMissingBean
    public LoadBalancerCacheProperties loadBalancerCacheProperties(
            ObjectProvider<AtengGatewayProperties> propertiesProvider) {
        LoadBalancerCacheProperties cacheProperties = new LoadBalancerCacheProperties();
        Duration ttl = Duration.ofSeconds(3);
        if (propertiesProvider.getIfAvailable() != null
                && propertiesProvider.getIfAvailable().getTraffic() != null
                && propertiesProvider.getIfAvailable().getTraffic().getCacheTtl() != null) {
            ttl = propertiesProvider.getIfAvailable().getTraffic().getCacheTtl();
        }
        cacheProperties.setTtl(ttl);
        return cacheProperties;
    }
}
