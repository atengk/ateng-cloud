package io.github.atengk.cloud.sample.seata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Seata 分布式事务增强场景样例启动入口
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootApplication
public class SampleSeataApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleSeataApplication.class, args);
    }
}
