package io.github.atengk.cloud.gateway.loadbalancer;

import io.github.atengk.cloud.gateway.properties.AtengGatewayProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 响应式网关金丝雀灰度负载均衡器客户端配置类
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
        ServiceInstanceListSupplier delegate = ServiceInstanceListSupplier.builder()
                .withDiscoveryClient()
                .withCaching()
                .build(context);

        AtengGatewayTrafficProperties trafficProperties = propertiesProvider.getIfAvailable() != null
                ? propertiesProvider.getIfAvailable().getTraffic()
                : new AtengGatewayTrafficProperties();

        return new GrayServiceInstanceListSupplier(delegate, trafficProperties);
    }
}
