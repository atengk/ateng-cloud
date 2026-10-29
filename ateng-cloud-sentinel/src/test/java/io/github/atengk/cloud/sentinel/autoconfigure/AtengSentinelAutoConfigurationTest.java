package io.github.atengk.cloud.sentinel.autoconfigure;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import io.github.atengk.cloud.sentinel.handler.AtengBlockExceptionHandler;
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
    @DisplayName("默认装配：AtengBlockExceptionHandler 成功注入")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengBlockExceptionHandler.class);
            assertThat(context).hasSingleBean(BlockExceptionHandler.class);
        });
    }

    @Test
    @DisplayName("全局禁用：ateng.cloud.sentinel.enabled=false 时不装配任何 Bean")
    void testDisabledGlobally() {
        contextRunner.withPropertyValues("ateng.cloud.sentinel.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengBlockExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(BlockExceptionHandler.class);
                });
    }

    @Test
    @DisplayName("局部禁用：ateng.cloud.sentinel.block-handler.enabled=false 时不装配处理器")
    void testBlockHandlerDisabled() {
        contextRunner.withPropertyValues("ateng.cloud.sentinel.block-handler.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengBlockExceptionHandler.class);
                });
    }

    @Test
    @DisplayName("自定义覆盖：业务显式声明 BlockExceptionHandler 时，框架默认 Bean 自动让位")
    void testCustomBlockExceptionHandlerBackoff() {
        contextRunner.withUserConfiguration(CustomBlockHandlerConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(BlockExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(AtengBlockExceptionHandler.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBlockHandlerConfig {
        @Bean
        public BlockExceptionHandler customHandler() {
            return (request, response, resourceName, e) -> response.setStatus(418);
        }
    }
}
