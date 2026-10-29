package io.github.atengk.cloud.nacos.loadbalancer;

import io.github.atengk.cloud.nacos.config.AtengNacosAutoConfiguration;
import io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AtengNacosLoadBalancerAutoConfiguration} 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
class AtengNacosLoadBalancerAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengNacosAutoConfiguration.class,
                    AtengNacosLoadBalancerAutoConfiguration.class));

    @Test
    @DisplayName("默认装配：LoadBalancerAutoConfiguration 自动激活，自适应计算器默认不激活")
    void testDefaultAutoconfiguration() {
        contextRunner
                .withPropertyValues("ateng.cloud.nacos.drain-timeout=10ms")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengNacosLoadBalancerAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(AdaptiveInstanceWeightCalculator.class);
                });
    }

    @Test
    @DisplayName("自适应加权装配：开启 adaptive-weight.enabled=true 时自动注册计算器")
    void testAdaptiveWeightCalculatorAutoConfigured() {
        contextRunner
                .withPropertyValues(
                        "ateng.cloud.nacos.drain-timeout=10ms",
                        "ateng.cloud.nacos.loadbalancer.adaptive-weight.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(InstanceWeightCalculator.class);
                    assertThat(context).hasSingleBean(AdaptiveInstanceWeightCalculator.class);
                });
    }

    @Test
    @DisplayName("自定义覆盖：用户显式注册自定义 InstanceWeightCalculator 时平滑覆盖框架默认 Bean")
    void testCustomInstanceWeightCalculatorOverridesDefault() {
        contextRunner
                .withUserConfiguration(CustomCalculatorConfig.class)
                .withPropertyValues(
                        "ateng.cloud.nacos.drain-timeout=10ms",
                        "ateng.cloud.nacos.loadbalancer.adaptive-weight.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(InstanceWeightCalculator.class);
                    assertThat(context).doesNotHaveBean(AdaptiveInstanceWeightCalculator.class);
                    InstanceWeightCalculator calculator = context.getBean(InstanceWeightCalculator.class);
                    assertThat(calculator.calculateWeight(null, 10.0)).isEqualTo(99.0);
                });
    }

    @Test
    @DisplayName("显式禁用：当 ateng.cloud.nacos.loadbalancer.enabled=false 时装配静默关闭")
    void testDisabledAutoconfiguration() {
        contextRunner
                .withPropertyValues(
                        "ateng.cloud.nacos.drain-timeout=10ms",
                        "ateng.cloud.nacos.loadbalancer.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengNacosLoadBalancerAutoConfiguration.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomCalculatorConfig {
        @Bean
        public InstanceWeightCalculator customCalculator() {
            return (inst, w) -> 99.0;
        }
    }
}
