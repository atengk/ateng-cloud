package io.github.atengk.cloud.traffic.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 流量治理客户端负载均衡基线环境后置处理器
 *
 * <p>核心治理语义：
 * 1. 预设 {@code spring.cloud.loadbalancer.cache.ttl=3s}（从原生 35 秒收敛至 3 秒，消除灰度切流时的实例缓存盲区）；
 * 2. 预设 {@code ateng.cloud.nacos.loadbalancer.client-supplier-enabled=false}，将客户端子上下文完全交付三层复合责任链接管；
 * 3. 挂载至最低优先级，确保业务在 {@code application.yml} 中的显式声明拥有最高优先级并平滑生效；
 * 4. 采用 {@code EnvironmentPostProcessor.imports} 与 {@code spring.factories} 双通道注册，防御测试框架与子上下文兼容性。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class AtengTrafficEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "atengTrafficDefaultProperties";
    public static final String DEFAULT_CACHE_TTL = "3s";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String cacheTtl = environment.getProperty("ateng.cloud.traffic.cache-ttl");
        if (!StringUtils.hasText(cacheTtl)) {
            cacheTtl = DEFAULT_CACHE_TTL;
        }

        Map<String, Object> defaults = new HashMap<>();
        defaults.put("spring.cloud.loadbalancer.cache.ttl", cacheTtl);

        // 客户端子上下文由 AtengTrafficLoadBalancerClientConfiguration 复合责任链接管，解耦 Nacos 默认独立提供者
        defaults.put("ateng.cloud.nacos.loadbalancer.client-supplier-enabled", "false");

        // 挂载至最低优先级，确保用户的 application.yml 与显式配置绝对优先生效
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
