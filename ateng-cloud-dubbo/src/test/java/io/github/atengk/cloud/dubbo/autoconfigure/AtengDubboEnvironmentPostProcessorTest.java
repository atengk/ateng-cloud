package io.github.atengk.cloud.dubbo.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dubbo 生产级安全基线环境后置处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class AtengDubboEnvironmentPostProcessorTest {

    private final AtengDubboEnvironmentPostProcessor processor = new AtengDubboEnvironmentPostProcessor();

    @Test
    @DisplayName("默认情况下注入 15000ms 优雅停机安全兜底基线")
    void testDefaultShutdownWait() {
        StandardEnvironment environment = new StandardEnvironment();
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("dubbo.application.shut-down.wait")).isEqualTo("15000");
        assertThat(environment.getProperty("dubbo.application.shutwait")).isEqualTo("15000");
        assertThat(environment.getPropertySources().contains("atengDubboDefaultProperties")).isTrue();
    }

    @Test
    @DisplayName("支持秒级单位 (s) 自适应解析与转换")
    void testSecondsDurationParsing() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("testSource",
                Map.of("ateng.cloud.dubbo.application.shut-wait", "30s")));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("dubbo.application.shut-down.wait")).isEqualTo("30000");
        assertThat(environment.getProperty("dubbo.application.shutwait")).isEqualTo("30000");
    }

    @Test
    @DisplayName("支持分钟单位 (m) 与 ISO-8601 (PT10S) 及毫秒 (ms) 解析")
    void testVariousDurationUnitsParsing() {
        // 1. 分钟解析
        StandardEnvironment envMinutes = new StandardEnvironment();
        envMinutes.getPropertySources().addFirst(new MapPropertySource("mSource",
                Map.of("ateng.cloud.dubbo.application.shutwait", "2m")));
        processor.postProcessEnvironment(envMinutes, new SpringApplication());
        assertThat(envMinutes.getProperty("dubbo.application.shut-down.wait")).isEqualTo("120000");

        // 2. 毫秒解析
        StandardEnvironment envMillis = new StandardEnvironment();
        envMillis.getPropertySources().addFirst(new MapPropertySource("msSource",
                Map.of("ateng.cloud.dubbo.application.shut-wait", "800ms")));
        processor.postProcessEnvironment(envMillis, new SpringApplication());
        assertThat(envMillis.getProperty("dubbo.application.shut-down.wait")).isEqualTo("800");

        // 3. ISO-8601 格式
        StandardEnvironment envIso = new StandardEnvironment();
        envIso.getPropertySources().addFirst(new MapPropertySource("isoSource",
                Map.of("ateng.cloud.dubbo.application.shut-wait", "PT10S")));
        processor.postProcessEnvironment(envIso, new SpringApplication());
        assertThat(envIso.getProperty("dubbo.application.shut-down.wait")).isEqualTo("10000");

        // 4. 纯数字毫秒
        StandardEnvironment envNumeric = new StandardEnvironment();
        envNumeric.getPropertySources().addFirst(new MapPropertySource("numSource",
                Map.of("ateng.cloud.dubbo.application.shut-wait", "25000")));
        processor.postProcessEnvironment(envNumeric, new SpringApplication());
        assertThat(envNumeric.getProperty("dubbo.application.shut-down.wait")).isEqualTo("25000");
    }

    @Test
    @DisplayName("非法与空白字符串格式自动优雅降级为 15000ms 默认值")
    void testInvalidDurationFallback() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("invalidSource",
                Map.of("ateng.cloud.dubbo.application.shut-wait", "invalid-format")));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("dubbo.application.shut-down.wait")).isEqualTo("15000");
    }

    @Test
    @DisplayName("验证后置处理器处于最低优先级以保障用户显式配置优先")
    void testProcessorOrder() {
        assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
