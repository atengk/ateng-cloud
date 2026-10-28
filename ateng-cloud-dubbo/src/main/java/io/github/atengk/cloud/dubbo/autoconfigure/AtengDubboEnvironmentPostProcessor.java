package io.github.atengk.cloud.dubbo.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Dubbo 生产级安全基线环境后置处理器
 * <p>
 * 为 Dubbo 核心停机参数注入生产安全默认基线：
 * 预设 dubbo.application.shut-down.wait=15000 与 dubbo.application.shutwait=15000（15秒超时硬兜底，排空在途请求）；
 * 若用户已在 ateng.cloud.dubbo.application.shut-wait 中指定，则自动对齐转换；
 * 若用户已在 application.yml 中显式配置 native dubbo 属性，则以最低优先级挂载确保用户配置绝对优先生效。
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengDubboEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "atengDubboDefaultProperties";
    private static final long DEFAULT_SHUTDOWN_WAIT_MILLIS = 15000L;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String shutWait = environment.getProperty("ateng.cloud.dubbo.application.shut-wait");
        if (shutWait == null) {
            shutWait = environment.getProperty("ateng.cloud.dubbo.application.shutwait");
        }
        long waitMillis = parseDurationMillis(shutWait, DEFAULT_SHUTDOWN_WAIT_MILLIS);

        Map<String, Object> defaults = new HashMap<>();
        defaults.put("dubbo.application.shut-down.wait", String.valueOf(waitMillis));
        defaults.put("dubbo.application.shutwait", String.valueOf(waitMillis));

        // 挂载至最低优先级，确保用户的 application.yml 与显式配置绝对优先生效
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    private static long parseDurationMillis(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String trimmed = value.trim().toLowerCase();
        try {
            if (trimmed.endsWith("ms")) {
                return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim());
            } else if (trimmed.endsWith("s")) {
                return Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()) * 1000L;
            } else if (trimmed.endsWith("m")) {
                return Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()) * 60_000L;
            } else if (trimmed.startsWith("pt")) {
                return java.time.Duration.parse(value.trim()).toMillis();
            } else {
                return Long.parseLong(trimmed);
            }
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
