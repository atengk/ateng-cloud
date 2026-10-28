package io.github.atengk.cloud.sample.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
}
