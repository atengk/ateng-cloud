package io.github.atengk.cloud.traffic.autoconfigure;

import io.github.atengk.cloud.traffic.actuator.TrafficEndpoint;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.context.TrafficContextSnapshotProvider;
import io.github.atengk.cloud.traffic.filter.TrafficContextFilter;
import io.github.atengk.cloud.traffic.metrics.TrafficMetricsManager;
import io.github.atengk.cloud.traffic.rule.hash.StickyHashPartitioner;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficPredicateMatcher;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
import io.github.atengk.cloud.traffic.rule.provider.PropertiesTrafficRuleProvider;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

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

    @AfterEach
    void tearDown() {
        TrafficContextHolder.clear();
    }

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
            assertThat(context).hasSingleBean(TrafficRuleProvider.class);
            assertThat(context).hasSingleBean(PropertiesTrafficRuleProvider.class);
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

    @Test
    @DisplayName("测试通过 ateng.cloud.traffic.rules 绑定本地静态规则并自动注入 PropertiesTrafficRuleProvider")
    void testPropertiesTrafficRuleProviderBinding() {
        nonWebRunner
                .withPropertyValues(
                        "ateng.cloud.traffic.rules[0].id=local-canary",
                        "ateng.cloud.traffic.rules[0].priority=5",
                        "ateng.cloud.traffic.rules[0].target-tag=v2"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PropertiesTrafficRuleProvider.class);
                    TrafficRuleProvider provider = context.getBean(TrafficRuleProvider.class);
                    assertThat(provider.getRules()).hasSize(1);
                    assertThat(provider.getRules().get(0).getId()).isEqualTo("local-canary");
                    assertThat(provider.getRules().get(0).getTargetTag()).isEqualTo("v2");
                });
    }

    @Test
    @DisplayName("测试自动装配 trafficRestClientCustomizer 并通过 RestClient 自动透传且不覆盖显式同名 Header")
    void testTrafficRestClientCustomizerAutoPropagation() {
        nonWebRunner.run(context -> {
            assertThat(context).hasBean("trafficRestClientCustomizer");
            RestClientCustomizer customizer = context.getBean("trafficRestClientCustomizer", RestClientCustomizer.class);

            AtomicReference<String> capturedHeader = new AtomicReference<>();
            RestClient.Builder builder = RestClient.builder()
                    .requestFactory((uri, method) -> new MockClientHttpRequest(method, uri) {
                        @Override
                        protected org.springframework.http.client.ClientHttpResponse executeInternal() {
                            capturedHeader.set(getHeaders().getFirst(TrafficConstants.HEADER_TRAFFIC_TAG));
                            return new MockClientHttpResponse("ok".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                        }
                    });
            customizer.customize(builder);
            RestClient restClient = builder.build();

            try {
                // 1. 仅上下文存在 tag -> 自动注入 X-Traffic-Tag
                TrafficContextHolder.setTag("canary-restclient");
                restClient.method(HttpMethod.GET).uri("http://order-service/api/ping").retrieve().toBodilessEntity();
                assertThat(capturedHeader.get()).isEqualTo("canary-restclient");

                // 2. 调用方显式传入有效同名 Header -> 保留显式标头不覆盖
                capturedHeader.set(null);
                restClient.method(HttpMethod.GET).uri("http://order-service/api/ping")
                        .header(TrafficConstants.HEADER_TRAFFIC_TAG, "explicit-v9")
                        .retrieve().toBodilessEntity();
                assertThat(capturedHeader.get()).isEqualTo("explicit-v9");
            } finally {
                TrafficContextHolder.clear();
            }
        });
    }
}
