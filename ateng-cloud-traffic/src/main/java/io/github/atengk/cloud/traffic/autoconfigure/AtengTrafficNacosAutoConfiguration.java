package io.github.atengk.cloud.traffic.autoconfigure;

import com.alibaba.nacos.api.config.ConfigService;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.github.atengk.cloud.traffic.nacos.NacosTrafficRuleListener;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Nacos 动态流量分流规则分级监听与零锁原子热更自动装配配置类
 * <p>
 * 遵循防御性自动装配契约：
 * 1. 条件守卫：必须在 Classpath 存在 NacosTemplate 与 ConfigService 且开启配置时生效；
 * 2. 顺序守卫：在基础流量装配类 AtengTrafficAutoConfiguration 之后装配；
 * 3. 缺省守卫：对外暴露 Bean 标配 @ConditionalOnMissingBean，支持下游应用随时平滑接管。
 *
 * @author Ateng
 * @since 2026-10-29
 */
@AutoConfiguration
@AutoConfigureAfter(AtengTrafficAutoConfiguration.class)
@ConditionalOnClass({NacosTemplate.class, ConfigService.class})
@ConditionalOnProperty(prefix = "ateng.cloud.traffic.nacos", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengTrafficProperties.class)
public class AtengTrafficNacosAutoConfiguration {

    /**
     * 注册 Nacos 动态流量分流规则分级监听器 Bean
     *
     * @param nacosTemplateProvider Nacos 操作门面提供者
     * @param properties            流量治理配置属性
     * @param environment           Spring 运行环境
     * @return NacosTrafficRuleListener
     */
    @Bean
    @ConditionalOnMissingBean(TrafficRuleProvider.class)
    public NacosTrafficRuleListener nacosTrafficRuleListener(
            ObjectProvider<NacosTemplate> nacosTemplateProvider,
            AtengTrafficProperties properties,
            Environment environment) {
        return new NacosTrafficRuleListener(nacosTemplateProvider, properties, environment);
    }
}
