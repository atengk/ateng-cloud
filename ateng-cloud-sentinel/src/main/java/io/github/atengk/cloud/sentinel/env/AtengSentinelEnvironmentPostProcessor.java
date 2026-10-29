package io.github.atengk.cloud.sentinel.env;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.util.ClassUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Sentinel 运行环境后置处理器
 * <p>
 * 自动探测 classpath 中的 OpenFeign，默认激活 {@code feign.sentinel.enabled=true}，
 * 防御因开发者遗漏配置导致熔断保护失效。
 * 支持通过 {@code application.yml} 显式声明 {@code feign.sentinel.enabled=false} 平滑覆盖。
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class AtengSentinelEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "atengSentinelDefaultProperties";
    public static final String FEIGN_SENTINEL_ENABLED = "feign.sentinel.enabled";
    public static final String FEIGN_CLIENT_CLASS = "org.springframework.cloud.openfeign.FeignClient";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ClassLoader classLoader = application.getClassLoader() != null
                ? application.getClassLoader()
                : ClassUtils.getDefaultClassLoader();

        if (ClassUtils.isPresent(FEIGN_CLIENT_CLASS, classLoader)) {
            MutablePropertySources propertySources = environment.getPropertySources();
            if (!propertySources.contains(PROPERTY_SOURCE_NAME)) {
                Map<String, Object> defaultProperties = new HashMap<>(2);
                defaultProperties.put(FEIGN_SENTINEL_ENABLED, "true");
                propertySources.addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaultProperties));
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
