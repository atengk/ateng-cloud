package io.github.atengk.cloud.traffic.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 负载均衡自动装配入口单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengTrafficLoadBalancerAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengTrafficLoadBalancerAutoConfiguration.class));

    @Test
    @DisplayName("测试自动装配默认激活并挂载 LoadBalancerClients 注解")
    void testAutoConfigurationActive() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AtengTrafficLoadBalancerAutoConfiguration.class);
            LoadBalancerClients annotation = AtengTrafficLoadBalancerAutoConfiguration.class.getAnnotation(LoadBalancerClients.class);
            assertThat(annotation).isNotNull();
        });
    }

    @Test
    @DisplayName("测试配置 ateng.cloud.traffic.enabled=false 时跳过自动装配")
    void testAutoConfigurationDisabled() {
        runner.withPropertyValues("ateng.cloud.traffic.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengTrafficLoadBalancerAutoConfiguration.class);
                });
    }
}
