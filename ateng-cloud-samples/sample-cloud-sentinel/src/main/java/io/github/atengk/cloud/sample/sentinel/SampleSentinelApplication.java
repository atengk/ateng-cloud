package io.github.atengk.cloud.sample.sentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Sentinel 场景演示应用
 *
 * @author Ateng
 * @since 2026-10-29
 */
@EnableFeignClients
@SpringBootApplication
public class SampleSentinelApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleSentinelApplication.class, args);
    }
}
