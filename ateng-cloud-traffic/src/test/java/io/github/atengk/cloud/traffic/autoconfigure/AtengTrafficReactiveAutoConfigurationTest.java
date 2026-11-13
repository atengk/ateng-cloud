package io.github.atengk.cloud.traffic.autoconfigure;

import io.github.atengk.cloud.traffic.filter.TrafficContextFilter;
import io.github.atengk.cloud.traffic.reactive.ReactiveTrafficContextWebFilter;
import io.github.atengk.cloud.traffic.reactive.TrafficExchangeFilterFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebFlux 响应式自动装配物理隔离单元测试
 *
 * @author Ateng
 * @since 2026-11-13
 */
class AtengTrafficReactiveAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner reactiveRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengTrafficAutoConfiguration.class,
                    AtengTrafficReactiveAutoConfiguration.class
            ));

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengTrafficAutoConfiguration.class,
                    AtengTrafficReactiveAutoConfiguration.class
            ));

    private final ApplicationContextRunner nonWebRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengTrafficAutoConfiguration.class,
                    AtengTrafficReactiveAutoConfiguration.class
            ));

    @Test
    @DisplayName("REACTIVE 环境下自动装配响应式过滤器与 WebClientCustomizer，且绝不装配 Servlet Filter")
    void testReactiveWebContext() {
        reactiveRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengTrafficReactiveAutoConfiguration.class);
            assertThat(context).hasSingleBean(ReactiveTrafficContextWebFilter.class);
            assertThat(context).hasSingleBean(TrafficExchangeFilterFunction.class);
            assertThat(context).hasBean("trafficWebClientCustomizer");
            assertThat(context).hasSingleBean(WebClientCustomizer.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);

            WebClientCustomizer customizer = context.getBean("trafficWebClientCustomizer", WebClientCustomizer.class);
            WebClient.Builder builder = WebClient.builder();
            customizer.customize(builder);
            builder.filters(filters -> assertThat(filters).anyMatch(TrafficExchangeFilterFunction.class::isInstance));
        });
    }

    @Test
    @DisplayName("SERVLET 环境下物理隔离跳过响应式自动装配，仅装配 Servlet TrafficContextFilter")
    void testServletContextIsolation() {
        servletRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AtengTrafficReactiveAutoConfiguration.class);
            assertThat(context).doesNotHaveBean(ReactiveTrafficContextWebFilter.class);
            assertThat(context).doesNotHaveBean(TrafficExchangeFilterFunction.class);
            assertThat(context).doesNotHaveBean(WebClientCustomizer.class);
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
            assertThat(context.getBean(FilterRegistrationBean.class).getFilter())
                    .isInstanceOf(TrafficContextFilter.class);
        });
    }

    @Test
    @DisplayName("非 Web 环境、关闭开关或缺失 WebFilter 类时跳过响应式装配")
    void testNonWebDisabledAndMissingClassGuards() {
        nonWebRunner.run(context -> assertThat(context).doesNotHaveBean(AtengTrafficReactiveAutoConfiguration.class));

        reactiveRunner
                .withPropertyValues("ateng.cloud.traffic.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AtengTrafficReactiveAutoConfiguration.class));

        reactiveRunner
                .withClassLoader(new FilteredClassLoader(WebFilter.class))
                .run(context -> assertThat(context).doesNotHaveBean(AtengTrafficReactiveAutoConfiguration.class));
    }
}
