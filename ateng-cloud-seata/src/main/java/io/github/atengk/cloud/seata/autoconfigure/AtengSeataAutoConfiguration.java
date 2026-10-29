package io.github.atengk.cloud.seata.autoconfigure;

import io.github.atengk.cloud.seata.constant.SeataConstants;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 分布式事务增强底座自动装配类
 *
 * @author Ateng
 * @since 2026-10-29
 */
@AutoConfiguration
@EnableConfigurationProperties(SeataProperties.class)
@ConditionalOnProperty(prefix = SeataConstants.PROPERTIES_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengSeataAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengSeataAutoConfiguration.class);

    public AtengSeataAutoConfiguration() {
        log.info("初始化 ateng-cloud-seata 分布式事务增强自动装配底座");
    }

    /**
     * 注册分布式事务增强标记 Bean（当配置生效时对外供给）
     *
     * @param properties Seata 配置属性
     * @return SeataMarker 标记实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SeataMarker seataMarker(SeataProperties properties) {
        log.debug("装配 SeataMarker 事务标记组件，全局事务默认超时: {}s，锁重试间隔: {}ms",
                properties.getTx().getDefaultTimeout().toSeconds(),
                properties.getLock().getRetryInterval().toMillis());
        return new SeataMarker(properties);
    }

    /**
     * 分布式事务基础设施装配守卫标记类
     */
    public static class SeataMarker {
        private final SeataProperties properties;

        public SeataMarker(SeataProperties properties) {
            this.properties = properties;
        }

        public SeataProperties getProperties() {
            return properties;
        }
    }
}
