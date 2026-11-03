package io.github.atengk.cloud.sample.smoke;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 微服务全反应堆离线冒烟自检样例工程主启动类
 * <p>
 * 聚合验证 ateng-cloud-all 起步依赖在全套骨干构件（Nacos、Feign、Dubbo、Sentinel、Traffic）装配下的平滑启动与自洽运行能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootApplication
@EnableFeignClients
@EnableDubbo
public class SampleSmokeTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleSmokeTestApplication.class, args);
    }
}
