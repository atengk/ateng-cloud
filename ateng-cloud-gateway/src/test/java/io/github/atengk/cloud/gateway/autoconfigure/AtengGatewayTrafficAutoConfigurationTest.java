package io.github.atengk.cloud.gateway.autoconfigure;

import io.github.atengk.cloud.gateway.filter.GatewayTrafficTagFilter;
import io.github.atengk.cloud.gateway.resolver.DefaultTrafficTagResolver;
import io.github.atengk.cloud.gateway.spi.TrafficTagResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关流量染色自动装配与 SPI 扩展覆盖测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengGatewayTrafficAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    AtengGatewayAutoConfiguration.class
            ));

    @Test
    @DisplayName("默认装配：激活 TrafficTagResolver 与 GatewayTrafficTagFilter")
    void testDefaultConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TrafficTagResolver.class);
            assertThat(context.getBean(TrafficTagResolver.class)).isInstanceOf(DefaultTrafficTagResolver.class);
            assertThat(context).hasSingleBean(GatewayTrafficTagFilter.class);
        });
    }

    @Test
    @DisplayName("自定义 Bean 覆盖：业务自定义 TrafficTagResolver 平滑接管默认实现")
    void testCustomTrafficTagResolverOverride() {
        contextRunner.withUserConfiguration(CustomResolverConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(TrafficTagResolver.class);
                    TrafficTagResolver resolver = context.getBean(TrafficTagResolver.class);
                    assertThat(resolver).isNotInstanceOf(DefaultTrafficTagResolver.class);
                    assertThat(resolver.resolveTag(null)).isEqualTo("custom-vip-tag");
                });
    }

    @Test
    @DisplayName("配置关闭：ateng.cloud.gateway.traffic.enabled=false 时跳过装配")
    void testDisabledConfiguration() {
        contextRunner.withPropertyValues("ateng.cloud.gateway.traffic.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TrafficTagResolver.class);
                    assertThat(context).doesNotHaveBean(GatewayTrafficTagFilter.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomResolverConfig {
        @Bean
        public TrafficTagResolver customTrafficTagResolver() {
            return exchange -> "custom-vip-tag";
        }
    }
}
