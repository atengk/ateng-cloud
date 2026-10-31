package io.github.atengk.cloud.sentinel.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AtengSentinelEnvironmentPostProcessor} 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengSentinelEnvironmentPostProcessorTest {

    private final AtengSentinelEnvironmentPostProcessor processor = new AtengSentinelEnvironmentPostProcessor();

    @Test
    @DisplayName("存在 FeignClient 且未显式配置时，默认注入 feign.sentinel.enabled=true")
    void testInjectDefaultFeignSentinelEnabled() {
        StandardEnvironment environment = new StandardEnvironment();
        SpringApplication application = new SpringApplication();

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty(AtengSentinelEnvironmentPostProcessor.FEIGN_SENTINEL_ENABLED))
                .isEqualTo("true");
        assertThat(environment.getProperty(AtengSentinelEnvironmentPostProcessor.PATH_MAPPING_SENTINEL_PANORAMA))
                .isEqualTo("sentinel-panorama");
        assertThat(environment.getPropertySources().contains(AtengSentinelEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isTrue();
    }

    @Test
    @DisplayName("用户显式配置 feign.sentinel.enabled=false 时，平滑覆盖默认值")
    void testExplicitFeignSentinelDisabled() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("userProperties", Map.of(AtengSentinelEnvironmentPostProcessor.FEIGN_SENTINEL_ENABLED, "false"))
        );
        SpringApplication application = new SpringApplication();

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty(AtengSentinelEnvironmentPostProcessor.FEIGN_SENTINEL_ENABLED))
                .isEqualTo("false");
    }

    @Test
    @DisplayName("无 FeignClient 类时，不注入任何默认属性")
    void testNoFeignClientClass() {
        StandardEnvironment environment = new StandardEnvironment();
        // 使用一个隔离的 URLClassLoader 模拟不包含 Feign 类的运行环境
        ClassLoader isolatedClassLoader = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader());
        ResourceLoader resourceLoader = new DefaultResourceLoader(isolatedClassLoader);
        SpringApplication application = new SpringApplication(resourceLoader);

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getPropertySources().contains(AtengSentinelEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
        assertThat(environment.containsProperty(AtengSentinelEnvironmentPostProcessor.FEIGN_SENTINEL_ENABLED))
                .isFalse();
    }

    @Test
    @DisplayName("验证执行优先级为最低优先级 LOWEST_PRECEDENCE")
    void testOrder() {
        assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
