package io.github.atengk.cloud.traffic.autoconfigure;

import feign.RequestInterceptor;
import io.github.atengk.cloud.traffic.interceptor.TrafficFeignRequestInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign 出站流量染色拦截器独立条件自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class AtengTrafficFeignAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengTrafficFeignAutoConfiguration.class));

    @Test
    @DisplayName("测试独立引入 Feign 且无外部上下文拦截器时，自动装配 TrafficFeignRequestInterceptor")
    void testStandaloneAutoConfiguration() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AtengTrafficFeignAutoConfiguration.class);
            assertThat(context).hasSingleBean(TrafficFeignRequestInterceptor.class);
            assertThat(context).hasBean("trafficFeignRequestInterceptor");
        });
    }

    @Test
    @DisplayName("测试当已存在 feignContextPropagationInterceptor 时，装配平滑让位退化")
    void testBackoffWhenFeignContextPropagationInterceptorPresent() {
        runner
                .withBean("feignContextPropagationInterceptor", RequestInterceptor.class, () -> template -> {})
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengTrafficFeignAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(TrafficFeignRequestInterceptor.class);
                    assertThat(context).doesNotHaveBean("trafficFeignRequestInterceptor");
                });
    }

    @Test
    @DisplayName("测试配置 ateng.cloud.traffic.enabled=false 时装配类条件跳过")
    void testDisabledConfiguration() {
        runner
                .withPropertyValues("ateng.cloud.traffic.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengTrafficFeignAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(TrafficFeignRequestInterceptor.class);
                });
    }

    @Test
    @DisplayName("测试开发者显式声明自定义 TrafficFeignRequestInterceptor 时平滑接管")
    void testCustomTrafficFeignRequestInterceptorTakeover() {
        TrafficFeignRequestInterceptor customInterceptor = new TrafficFeignRequestInterceptor();
        runner
                .withBean("customTrafficInterceptor", TrafficFeignRequestInterceptor.class, () -> customInterceptor)
                .run(context -> {
                    assertThat(context).hasSingleBean(TrafficFeignRequestInterceptor.class);
                    assertThat(context.getBean(TrafficFeignRequestInterceptor.class)).isSameAs(customInterceptor);
                    assertThat(context).doesNotHaveBean("trafficFeignRequestInterceptor");
                });
    }
}
