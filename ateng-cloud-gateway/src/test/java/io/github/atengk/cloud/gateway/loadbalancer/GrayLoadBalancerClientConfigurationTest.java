package io.github.atengk.cloud.gateway.loadbalancer;

import io.github.atengk.cloud.gateway.properties.AtengGatewayProperties;
import io.github.atengk.cloud.nacos.loadbalancer.AtengNacosLoadBalancerClientConfiguration;
import io.github.atengk.cloud.nacos.loadbalancer.NacosWarmupLoadBalancer;
import io.github.atengk.cloud.nacos.loadbalancer.ZonePreferenceServiceInstanceListSupplier;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.loadbalancer.cache.LoadBalancerCacheProperties;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link GrayLoadBalancerClientConfiguration} 客户端子上下文装配与消减竞态单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class GrayLoadBalancerClientConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    TestParentMockConfig.class,
                    GrayLoadBalancerClientConfiguration.class
            )
            .withPropertyValues(
                    "spring.cloud.loadbalancer.client.name=test-service",
                    "spring.cloud.loadbalancer.zone=zone-a"
            );

    @Test
    @DisplayName("默认装配：网关客户端子上下文自动创建复合责任链提供者与 3s 缓存属性")
    void testDefaultChildContextBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(LoadBalancerCacheProperties.class);
            LoadBalancerCacheProperties cacheProperties = context.getBean(LoadBalancerCacheProperties.class);
            assertThat(cacheProperties.getTtl()).isEqualTo(Duration.ofSeconds(3));

            assertThat(context).hasSingleBean(ServiceInstanceListSupplier.class);
            ServiceInstanceListSupplier supplier = context.getBean(ServiceInstanceListSupplier.class);
            // 顶层应为 ZonePreferenceServiceInstanceListSupplier
            assertThat(supplier).isInstanceOf(ZonePreferenceServiceInstanceListSupplier.class);

            ZonePreferenceServiceInstanceListSupplier zoneSupplier = (ZonePreferenceServiceInstanceListSupplier) supplier;
            assertThat(zoneSupplier.resolveTargetZone()).isEqualTo("zone-a");
            // 第二层为 GrayServiceInstanceListSupplier
            assertThat(zoneSupplier.getDelegate()).isInstanceOf(GrayServiceInstanceListSupplier.class);

            // 选择器为 NacosWarmupLoadBalancer
            assertThat(context).hasSingleBean(ReactorLoadBalancer.class);
            ReactorLoadBalancer<?> loadBalancer = context.getBean(ReactorLoadBalancer.class);
            assertThat(loadBalancer).isInstanceOf(NacosWarmupLoadBalancer.class);
        });
    }

    @Test
    @DisplayName("消减竞态：同时引入 AtengNacosLoadBalancerClientConfiguration 时平滑组合无 Bean 冲突")
    void testCoexistenceWithNacosClientConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        TestParentMockConfig.class,
                        AtengNacosLoadBalancerClientConfiguration.class,
                        GrayLoadBalancerClientConfiguration.class
                )
                .withPropertyValues(
                        "spring.cloud.loadbalancer.client.name=order-service",
                        "spring.cloud.loadbalancer.zone=zone-b"
                )
                .run(context -> {
                    // 严格断言：消除互斥抢占，ServiceInstanceListSupplier 依然唯一且由网关复合责任链接管
                    assertThat(context).hasSingleBean(ServiceInstanceListSupplier.class);
                    ServiceInstanceListSupplier supplier = context.getBean(ServiceInstanceListSupplier.class);
                    assertThat(supplier).isInstanceOf(ZonePreferenceServiceInstanceListSupplier.class);
                    ZonePreferenceServiceInstanceListSupplier zoneSupplier = (ZonePreferenceServiceInstanceListSupplier) supplier;
                    assertThat(zoneSupplier.getDelegate()).isInstanceOf(GrayServiceInstanceListSupplier.class);

                    // 选择器依然唯一且为 NacosWarmupLoadBalancer
                    assertThat(context).hasSingleBean(ReactorLoadBalancer.class);
                    assertThat(context.getBean(ReactorLoadBalancer.class)).isInstanceOf(NacosWarmupLoadBalancer.class);

                    // 缓存 TTL 依然收敛为 3s
                    assertThat(context).hasSingleBean(LoadBalancerCacheProperties.class);
                    assertThat(context.getBean(LoadBalancerCacheProperties.class).getTtl()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestParentMockConfig {
        @Bean
        public AtengGatewayProperties atengGatewayProperties() {
            return new AtengGatewayProperties();
        }

        @Bean
        public AtengNacosProperties atengNacosProperties() {
            return new AtengNacosProperties();
        }

        @Bean
        public ReactiveDiscoveryClient reactiveDiscoveryClient() {
            return mock(ReactiveDiscoveryClient.class);
        }

        @Bean
        public LoadBalancerClientFactory loadBalancerClientFactory() {
            return mock(LoadBalancerClientFactory.class);
        }
    }
}
