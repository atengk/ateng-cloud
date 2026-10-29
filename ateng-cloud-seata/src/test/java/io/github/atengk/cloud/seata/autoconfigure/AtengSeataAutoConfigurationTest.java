package io.github.atengk.cloud.seata.autoconfigure;

import io.github.atengk.cloud.seata.aspect.SeataExceptionTranslationAspect;
import io.github.atengk.cloud.seata.autoconfigure.AtengSeataAutoConfiguration.SeataMarker;
import io.github.atengk.cloud.seata.interceptor.SeataFeignContextInterceptor;
import io.github.atengk.cloud.seata.interceptor.SeataWebHandlerInterceptor;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分布式事务增强自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengSeataAutoConfigurationTest {

    private final ApplicationContextRunner nonWebRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSeataAutoConfiguration.class));

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSeataAutoConfiguration.class));

    @Test
    @DisplayName("测试非 Web 环境下自动装配核心组件与默认属性")
    void testNonWebContext() {
        nonWebRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengSeataAutoConfiguration.class);
            assertThat(context).hasSingleBean(AtengSeataAutoConfiguration.SeataMarker.class);
            assertThat(context).hasSingleBean(SeataProperties.class);
            assertThat(context).hasSingleBean(SeataFeignContextInterceptor.class);
            assertThat(context).hasSingleBean(SeataExceptionTranslationAspect.class);
            assertThat(context).doesNotHaveBean(SeataWebHandlerInterceptor.class);

            SeataProperties properties = context.getBean(SeataProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getTx().getDefaultTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getLock().getRetryInterval()).isEqualTo(Duration.ofMillis(50));
            assertThat(properties.getLock().getRetryTimes()).isEqualTo(30);
            assertThat(properties.getDegrade().isEnabled()).isFalse();
            assertThat(properties.getTcc().isFenceEnabled()).isTrue();
        });
    }

    @Test
    @DisplayName("测试 Servlet Web 环境下自动装配 Web 入站拦截器与配置")
    void testServletWebContext() {
        servletRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengSeataAutoConfiguration.class);
            assertThat(context).hasSingleBean(SeataMarker.class);
            assertThat(context).hasSingleBean(SeataProperties.class);
            assertThat(context).hasSingleBean(SeataFeignContextInterceptor.class);
            assertThat(context).hasSingleBean(SeataWebHandlerInterceptor.class);
            assertThat(context).hasSingleBean(SeataWebMvcConfigurer.class);
            assertThat(context).hasSingleBean(SeataExceptionTranslationAspect.class);
        });
    }

    @Test
    @DisplayName("测试配置 ateng.cloud.seata.enabled=false 时装配类条件跳过")
    void testDisabledConfiguration() {
        servletRunner
                .withPropertyValues("ateng.cloud.seata.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengSeataAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(SeataMarker.class);
                    assertThat(context).doesNotHaveBean(SeataFeignContextInterceptor.class);
                    assertThat(context).doesNotHaveBean(SeataWebHandlerInterceptor.class);
                });
    }

    @Test
    @DisplayName("测试自定义 SeataMarker 时遵循 @ConditionalOnMissingBean 守卫")
    void testCustomMarkerOverride() {
        SeataProperties customProps = new SeataProperties();
        SeataMarker customMarker = new SeataMarker(customProps);

        nonWebRunner
                .withBean("customSeataMarker", SeataMarker.class, () -> customMarker)
                .run(context -> {
                    assertThat(context).hasSingleBean(SeataMarker.class);
                    assertThat(context.getBean(SeataMarker.class)).isSameAs(customMarker);
                });
    }
}
