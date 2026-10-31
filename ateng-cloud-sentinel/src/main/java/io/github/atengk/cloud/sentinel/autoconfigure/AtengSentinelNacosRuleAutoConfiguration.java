package io.github.atengk.cloud.sentinel.autoconfigure;

import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.sentinel.datasource.DefaultNacosDataSourceFactory;
import io.github.atengk.cloud.sentinel.datasource.NacosDataSourceFactory;
import io.github.atengk.cloud.sentinel.datasource.SentinelNacosRuleSubscriber;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelNacosRuleProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Sentinel Nacos 规则全家桶约定动态持久化自动装配
 *
 * @author Ateng
 * @since 2026-10-29
 */
@AutoConfiguration
@AutoConfigureAfter(AtengSentinelAutoConfiguration.class)
@ConditionalOnClass({BlockException.class, NacosDataSource.class})
@ConditionalOnProperty(prefix = "ateng.cloud.sentinel.nacos-rules", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AtengSentinelNacosRuleProperties.class)
public class AtengSentinelNacosRuleAutoConfiguration {

    /**
     * 注册 Nacos 数据源构造工厂 (支持下游通过 SPI 或测试桩无缝接管)
     */
    @Bean
    @ConditionalOnMissingBean(NacosDataSourceFactory.class)
    public NacosDataSourceFactory nacosDataSourceFactory() {
        return new DefaultNacosDataSourceFactory();
    }

    /**
     * 注册 Sentinel Nacos 5 大规则全家桶动态订阅器
     */
    @Bean
    @ConditionalOnMissingBean(SentinelNacosRuleSubscriber.class)
    public SentinelNacosRuleSubscriber sentinelNacosRuleSubscriber(
            AtengSentinelNacosRuleProperties properties,
            NacosDataSourceFactory dataSourceFactory,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            @Value("${spring.application.name:}") String appName,
            Environment environment) {
        return new SentinelNacosRuleSubscriber(properties, dataSourceFactory, objectMapperProvider, appName, environment);
    }
}
