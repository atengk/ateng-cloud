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
 * 为 Dubbo 核心参数注入生产安全默认基线：
 * 1. 预设 dubbo.application.shut-down.wait=15000 与 dubbo.application.shutwait=15000（15秒超时硬兜底，排空在途请求）；
 * 2. 预设 dubbo.protocol.payload=16777216 与 dubbo.provider.payload=16777216 (16MB 载荷硬防爆上限)；
 * 3. 预设 dubbo.consumer.retries=0 (非幂等写防雪崩)；
 * 用户在 application.yml 中显式声明的配置拥有更高优先级，可无缝接管覆盖。
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengDubboEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "atengDubboDefaultProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("dubbo.application.shut-down.wait", "15000");
        defaults.put("dubbo.application.shutwait", "15000");
        defaults.put("dubbo.protocol.payload", "16777216");
        defaults.put("dubbo.provider.payload", "16777216");
        defaults.put("dubbo.consumer.retries", "0");
        defaults.put("dubbo.provider.retries", "0");

        // 挂载至最低优先级，确保用户的 application.yml 与显式配置绝对优先生效
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
