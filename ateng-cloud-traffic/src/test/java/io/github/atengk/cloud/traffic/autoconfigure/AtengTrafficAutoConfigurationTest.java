package io.github.atengk.cloud.traffic.autoconfigure;

import io.github.atengk.cloud.traffic.context.TrafficContextSnapshotProvider;
import io.github.atengk.cloud.traffic.filter.TrafficContextFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路流量治理自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengTrafficAutoConfigurationTest {

    private final ApplicationContextRunner nonWebRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengTrafficAutoConfiguration.class));

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengTrafficAutoConfiguration.class));

    @Test
    @DisplayName("测试非 Web 环境下装配核心基础设施")
    void testNonWebContext() {
        nonWebRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengTrafficAutoConfiguration.class);
            assertThat(context).hasSingleBean(TrafficContextSnapshotProvider.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
        });
    }

    @Test
    @DisplayName("测试 Servlet Web 环境下自动装配 TrafficContextFilter")
    void testServletWebContext() {
        servletRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengTrafficAutoConfiguration.class);
            assertThat(context).hasSingleBean(TrafficContextSnapshotProvider.class);
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
            assertThat(context.getBean(FilterRegistrationBean.class).getFilter())
                    .isInstanceOf(TrafficContextFilter.class);
        });
    }

    @Test
    @DisplayName("测试配置 ateng.cloud.traffic.enabled=false 时装配类条件跳过")
    void testDisabledConfiguration() {
        servletRunner
                .withPropertyValues("ateng.cloud.traffic.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengTrafficAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                });
    }
}
