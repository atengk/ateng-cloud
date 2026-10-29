package io.github.atengk.cloud.traffic.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 负载均衡基线环境后置处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengTrafficEnvironmentPostProcessorTest {

    private final AtengTrafficEnvironmentPostProcessor processor = new AtengTrafficEnvironmentPostProcessor();

    @Test
    @DisplayName("测试注入默认负载均衡缓存 TTL 与禁用 Nacos 独立提供者属性")
    void testDefaultProperties() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.cloud.loadbalancer.cache.ttl")).isEqualTo("3s");
        assertThat(environment.getProperty("ateng.cloud.nacos.loadbalancer.client-supplier-enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("测试自定义 ateng.cloud.traffic.cache-ttl 配置透传对齐")
    void testCustomCacheTtl() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getSystemProperties().put("ateng.cloud.traffic.cache-ttl", "5s");

        try {
            processor.postProcessEnvironment(environment, new SpringApplication());
            assertThat(environment.getProperty("spring.cloud.loadbalancer.cache.ttl")).isEqualTo("5s");
        } finally {
            environment.getSystemProperties().remove("ateng.cloud.traffic.cache-ttl");
        }
    }
}
