package io.github.atengk.cloud.nacos.config;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Nacos 服务注册与配置中心自动装配入口
 *
 * @author Ateng
 * @since 2026-10-27
 */
@AutoConfiguration
@EnableConfigurationProperties(AtengNacosProperties.class)
@ConditionalOnProperty(prefix = "ateng.cloud.nacos", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengNacosAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengNacosAutoConfiguration.class);

    public AtengNacosAutoConfiguration() {
        log.info("ateng-cloud Nacos infrastructure autoconfiguration initialized.");
    }
}
