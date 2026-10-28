package io.github.atengk.cloud.sample.feign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Feign 声明式客户端自验样例工程主启动类
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootApplication
@EnableFeignClients
public class SampleFeignApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleFeignApplication.class, args);
    }
}
