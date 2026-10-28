package io.github.atengk.cloud.sample.dubbo;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Dubbo 微服务样例工程启动类
 *
 * @author Ateng
 * @since 2026-10-28
 */
@EnableDubbo
@SpringBootApplication
public class SampleDubboApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleDubboApplication.class, args);
    }
}
