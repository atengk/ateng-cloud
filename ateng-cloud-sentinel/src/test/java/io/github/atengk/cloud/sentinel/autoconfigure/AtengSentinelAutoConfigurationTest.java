package io.github.atengk.cloud.sentinel.autoconfigure;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.RequestOriginParser;
import com.alibaba.csp.sentinel.adapter.web.common.UrlCleaner;
import io.github.atengk.cloud.sentinel.advice.SentinelBlockExceptionAdvice;
import io.github.atengk.cloud.sentinel.cleaner.AtengUrlCleaner;
import io.github.atengk.cloud.sentinel.handler.AtengBlockExceptionHandler;
import io.github.atengk.cloud.sentinel.parser.AtengRequestOriginParser;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AtengSentinelAutoConfiguration} 自动装配测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengSentinelAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSentinelAutoConfiguration.class));

    @Test
    @DisplayName("默认装配：AtengBlockExceptionHandler, AtengUrlCleaner, AtengRequestOriginParser 均成功注入")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengBlockExceptionHandler.class);
            assertThat(context).hasSingleBean(BlockExceptionHandler.class);

            assertThat(context).hasSingleBean(AtengUrlCleaner.class);
            assertThat(context).hasSingleBean(UrlCleaner.class);

            assertThat(context).hasSingleBean(AtengRequestOriginParser.class);
            assertThat(context).hasSingleBean(RequestOriginParser.class);

            assertThat(context).hasSingleBean(SentinelBlockExceptionAdvice.class);
        });
    }

    @Test
    @DisplayName("全局禁用：ateng.cloud.sentinel.enabled=false 时不装配任何 Bean")
    void testDisabledGlobally() {
        contextRunner.withPropertyValues("ateng.cloud.sentinel.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengBlockExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(AtengUrlCleaner.class);
                    assertThat(context).doesNotHaveBean(AtengRequestOriginParser.class);
                    assertThat(context).doesNotHaveBean(SentinelBlockExceptionAdvice.class);
                });
    }

    @Test
    @DisplayName("局部禁用：各项独立开关为 false 时分别跳过对应装配")
    void testPartialDisabled() {
        contextRunner.withPropertyValues(
                "ateng.cloud.sentinel.block-handler.enabled=false",
                "ateng.cloud.sentinel.url-clean.enabled=false",
                "ateng.cloud.sentinel.origin-parser.enabled=false",
                "ateng.cloud.sentinel.block-advice.enabled=false"
        ).run(context -> {
            assertThat(context).doesNotHaveBean(AtengBlockExceptionHandler.class);
            assertThat(context).doesNotHaveBean(AtengUrlCleaner.class);
            assertThat(context).doesNotHaveBean(AtengRequestOriginParser.class);
            assertThat(context).doesNotHaveBean(SentinelBlockExceptionAdvice.class);
        });
    }

    @Test
    @DisplayName("自定义覆盖：业务显式声明自定义组件时，框架默认 Bean 自动让位")
    void testCustomComponentsBackoff() {
        contextRunner.withUserConfiguration(CustomComponentsConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(BlockExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(AtengBlockExceptionHandler.class);

                    assertThat(context).hasSingleBean(UrlCleaner.class);
                    assertThat(context).doesNotHaveBean(AtengUrlCleaner.class);

                    assertThat(context).hasSingleBean(RequestOriginParser.class);
                    assertThat(context).doesNotHaveBean(AtengRequestOriginParser.class);

                    assertThat(context).hasSingleBean(SentinelBlockExceptionAdvice.class);
                    assertThat(context).getBean(SentinelBlockExceptionAdvice.class).isSameAs(context.getBean("customBlockAdvice"));
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomComponentsConfig {
        @Bean
        public BlockExceptionHandler customHandler() {
            return (request, response, resourceName, e) -> response.setStatus(418);
        }

        @Bean
        public UrlCleaner customUrlCleaner() {
            return url -> "/custom-clean";
        }

        @Bean
        public RequestOriginParser customOriginParser() {
            return request -> "custom-origin";
        }

        @Bean
        public SentinelBlockExceptionAdvice customBlockAdvice() {
            return new SentinelBlockExceptionAdvice(new AtengSentinelProperties());
        }
    }
}
