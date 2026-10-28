package io.github.atengk.cloud.sample.gateway;

import io.github.atengk.cloud.gateway.provider.InMemorySignSecretProvider;
import io.github.atengk.cloud.gateway.spi.SignSecretProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 响应式网关自验场景启动类 (100% 离线自洽)
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootApplication
public class SampleGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleGatewayApplication.class, args);
    }

    /**
     * 注册示例开放接口密钥提供者，演示通过 SPI 扩展点接管密钥管理
     */
    @Bean
    public SignSecretProvider sampleSignSecretProvider() {
        InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
        provider.registerSecret("sample-app", "sample-secret-123456");
        return provider;
    }
}
