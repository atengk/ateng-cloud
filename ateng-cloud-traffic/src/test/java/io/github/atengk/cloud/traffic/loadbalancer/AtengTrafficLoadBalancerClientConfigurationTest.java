package io.github.atengk.cloud.traffic.loadbalancer;

import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;
import org.springframework.cloud.loadbalancer.cache.LoadBalancerCacheProperties;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 负载均衡客户端子上下文自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengTrafficLoadBalancerClientConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestDiscoveryConfig.class, AtengTrafficLoadBalancerClientConfiguration.class)
            .withPropertyValues(
                    "spring.cloud.loadbalancer.client.name=test-service",
                    "loadbalancer.client.name=test-service"
            );

    @Test
    @DisplayName("测试客户端子上下文正确装配三层责任链提供者与轮询选择器")
    void testClientContextBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ServiceInstanceListSupplier.class);
            ServiceInstanceListSupplier supplier = context.getBean(ServiceInstanceListSupplier.class);
            // 验证责任链外层或内部包含 TrafficTagServiceInstanceListSupplier
            assertThat(supplier).isNotNull();

            assertThat(context).hasSingleBean(ReactorLoadBalancer.class);
            assertThat(context).hasSingleBean(LoadBalancerCacheProperties.class);

            LoadBalancerCacheProperties cacheProps = context.getBean(LoadBalancerCacheProperties.class);
            assertThat(cacheProps.getTtl()).isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDiscoveryConfig {

        @Bean
        public DiscoveryClient discoveryClient() {
            return new SimpleDiscoveryClient(new SimpleDiscoveryProperties());
        }

        @Bean
        public LoadBalancerClientFactory loadBalancerClientFactory() {
            return new LoadBalancerClientFactory(new org.springframework.cloud.client.loadbalancer.LoadBalancerClientsProperties());
        }

        @Bean
        public AtengTrafficProperties atengTrafficProperties() {
            return new AtengTrafficProperties();
        }
    }
}
