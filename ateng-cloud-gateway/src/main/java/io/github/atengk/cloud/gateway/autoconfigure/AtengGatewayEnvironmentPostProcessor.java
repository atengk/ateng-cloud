package io.github.atengk.cloud.gateway.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 网关客户端负载均衡与高可用基线环境后置处理器
 *
 * <p>核心治理语义：
 * 为响应式网关注入默认 Spring Cloud LoadBalancer 缓存时效基线属性：
 * 预设 {@code spring.cloud.loadbalancer.cache.ttl=3s}（从原生 35 秒收敛至 3 秒，消除停机摘流盲区）；
 * 若用户在 {@code ateng.cloud.gateway.traffic.cache-ttl} 中配置了自定义时效，自动对齐转换；
 * 挂载至最低优先级，确保用户在 {@code application.yml} 中的显式配置具有最高优先级并平滑生效。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class AtengGatewayEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "atengGatewayDefaultProperties";
    public static final String DEFAULT_CACHE_TTL = "3s";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String cacheTtl = environment.getProperty("ateng.cloud.gateway.traffic.cache-ttl");
        if (!StringUtils.hasText(cacheTtl)) {
            cacheTtl = DEFAULT_CACHE_TTL;
        }

        Map<String, Object> defaults = new HashMap<>();
        defaults.put("spring.cloud.loadbalancer.cache.ttl", cacheTtl);

        String gatewayZone = environment.getProperty("ateng.cloud.gateway.traffic.zone");
        if (StringUtils.hasText(gatewayZone)) {
            defaults.put("spring.cloud.loadbalancer.zone", gatewayZone.trim());
        }

        // 网关客户端子上下文由 GrayLoadBalancerClientConfiguration 复合责任链接管，解耦 Nacos 默认独立提供者
        defaults.put("ateng.cloud.nacos.loadbalancer.client-supplier-enabled", "false");

        // 挂载至最低优先级，确保用户的 application.yml 与显式配置绝对优先生效
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
