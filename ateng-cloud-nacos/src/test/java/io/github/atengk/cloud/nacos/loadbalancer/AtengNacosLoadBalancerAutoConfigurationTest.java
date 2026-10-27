package io.github.atengk.cloud.nacos.loadbalancer;

import io.github.atengk.cloud.nacos.config.AtengNacosAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
    @DisplayName("默认装配：LoadBalancerAutoConfiguration 自动激活")
    void testDefaultAutoconfiguration() {
        contextRunner
                .withPropertyValues("ateng.cloud.nacos.drain-timeout=10ms")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengNacosLoadBalancerAutoConfiguration.class);
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
}
