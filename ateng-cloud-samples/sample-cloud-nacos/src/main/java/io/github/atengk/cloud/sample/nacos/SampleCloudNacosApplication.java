package io.github.atengk.cloud.sample.nacos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Nacos 服务注册与配置中心 1:1 自验场景工程主入口
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootApplication
public class SampleCloudNacosApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleCloudNacosApplication.class, args);
    }
}
