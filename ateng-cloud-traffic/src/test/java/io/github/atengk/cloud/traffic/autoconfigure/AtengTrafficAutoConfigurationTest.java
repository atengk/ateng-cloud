package io.github.atengk.cloud.traffic.autoconfigure;

import io.github.atengk.cloud.traffic.actuator.TrafficEndpoint;
import io.github.atengk.cloud.traffic.context.TrafficContextSnapshotProvider;
import io.github.atengk.cloud.traffic.filter.TrafficContextFilter;
import io.github.atengk.cloud.traffic.metrics.TrafficMetricsManager;
import io.github.atengk.cloud.traffic.rule.hash.StickyHashPartitioner;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficPredicateMatcher;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
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
            assertThat(context).hasSingleBean(StickyHashPartitioner.class);
            assertThat(context).hasSingleBean(TrafficPredicateMatcher.class);
            assertThat(context).hasSingleBean(TrafficRuleMatcher.class);
            assertThat(context).hasSingleBean(TrafficMetricsManager.class);
            assertThat(context).hasSingleBean(TrafficEndpoint.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
        });
    }

    @Test
    @DisplayName("测试 Servlet Web 环境下自动装配 TrafficContextFilter")
    void testServletWebContext() {
        servletRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengTrafficAutoConfiguration.class);
            assertThat(context).hasSingleBean(TrafficContextSnapshotProvider.class);
            assertThat(context).hasSingleBean(StickyHashPartitioner.class);
            assertThat(context).hasSingleBean(TrafficPredicateMatcher.class);
            assertThat(context).hasSingleBean(TrafficRuleMatcher.class);
            assertThat(context).hasSingleBean(TrafficMetricsManager.class);
            assertThat(context).hasSingleBean(TrafficEndpoint.class);
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
            assertThat(context.getBean(FilterRegistrationBean.class).getFilter())
                    .isInstanceOf(TrafficContextFilter.class);
        });
    }

    @Test
    @DisplayName("测试配置 ateng.cloud.traffic.actuator.enabled=false 时只禁用端点而不影响核心治理与度量")
    void testActuatorEndpointDisabled() {
        servletRunner
                .withPropertyValues("ateng.cloud.traffic.actuator.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengTrafficAutoConfiguration.class);
                    assertThat(context).hasSingleBean(TrafficMetricsManager.class);
                    assertThat(context).doesNotHaveBean(TrafficEndpoint.class);
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

    @Test
    @DisplayName("测试当 Classpath 缺失 MeterRegistry 时，平滑跳过 TrafficMetricsManager 装配")
    void testMetricsManagerDisabledWhenMeterRegistryMissing() {
        nonWebRunner
                .withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(io.micrometer.core.instrument.MeterRegistry.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TrafficMetricsManager.class);
                });
    }
}
