package io.github.atengk.cloud.gateway.autoconfigure;

import io.github.atengk.cloud.gateway.route.GatewayRouteRefreshListener;
import io.github.atengk.cloud.gateway.route.NacosRouteDefinitionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关双轨动态路由自动装配测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengGatewayDynamicRouteAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    AtengGatewayAutoConfiguration.class
            ));

    @Test
    @DisplayName("默认装配：激活 GatewayRouteRefreshListener")
    void testDefaultConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(GatewayRouteRefreshListener.class);
        });
    }

    @Test
    @DisplayName("配置关闭：ateng.cloud.gateway.dynamic-route.enabled=false 时跳过动态路由装配")
    void testDisabledConfiguration() {
        contextRunner.withPropertyValues("ateng.cloud.gateway.dynamic-route.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(GatewayRouteRefreshListener.class);
                    assertThat(context).doesNotHaveBean(NacosRouteDefinitionRepository.class);
                });
    }
}
