package io.github.atengk.cloud.dubbo.autoconfigure;

import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.spring.context.config.DubboConfigBeanCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Dubbo 核心通信与治理自动装配
 *
 * @author Ateng
 * @since 2026-10-28
 */
@AutoConfiguration
@ConditionalOnClass(AbstractConfig.class)
@EnableConfigurationProperties(AtengDubboProperties.class)
@ConditionalOnProperty(prefix = "ateng.cloud.dubbo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengDubboAutoConfiguration {

    /**
     * 注册 Dubbo 配置对象定制器，为 Consumer、Provider、Protocol 与 Application 注入生产安全基线默认值
     *
     * @param properties Dubbo 配置属性
     * @return DubboConfigBeanCustomizer 实例
     */
    @Bean
    @ConditionalOnMissingBean(name = "atengDubboConfigBeanCustomizer")
    public DubboConfigBeanCustomizer atengDubboConfigBeanCustomizer(AtengDubboProperties properties) {
        io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder.set(properties);
        return new DubboConfigBeanCustomizer() {
            @Override
            public void customize(String beanName, AbstractConfig dubboConfig) {
                if (dubboConfig instanceof ConsumerConfig consumerConfig) {
                    if (consumerConfig.getRetries() == null) {
                        consumerConfig.setRetries(properties.getRetries());
                    }
                    if (consumerConfig.getTimeout() == null) {
                        consumerConfig.setTimeout((int) properties.getTimeout().toMillis());
                    }
                } else if (dubboConfig instanceof ProviderConfig providerConfig) {
                    if (providerConfig.getRetries() == null) {
                        providerConfig.setRetries(properties.getRetries());
                    }
                    if (providerConfig.getTimeout() == null) {
                        providerConfig.setTimeout((int) properties.getTimeout().toMillis());
                    }
                    if (providerConfig.getPayload() == null && properties.getPayload() != null) {
                        providerConfig.setPayload((int) properties.getPayload().toBytes());
                    }
                } else if (dubboConfig instanceof ProtocolConfig protocolConfig) {
                    var protocolProps = properties.getProtocol();
                    if (protocolConfig.getName() == null && protocolProps != null) {
                        protocolConfig.setName(protocolProps.getName());
                    }
                    if (protocolConfig.getPort() == null && protocolProps != null) {
                        protocolConfig.setPort(protocolProps.getPort());
                    }
                    if (protocolConfig.getSerialization() == null && protocolProps != null) {
                        protocolConfig.setSerialization(protocolProps.getSerialization());
                    }
                    if (protocolConfig.getPayload() == null && properties.getPayload() != null) {
                        protocolConfig.setPayload((int) properties.getPayload().toBytes());
                    }
                } else if (dubboConfig instanceof ApplicationConfig applicationConfig) {
                    var appProps = properties.getApplication();
                    if (applicationConfig.getRegisterMode() == null && appProps != null) {
                        applicationConfig.setRegisterMode(appProps.getRegisterMode());
                    }
                }
            }

            @Override
            public int getOrder() {
                // 最低优先级，确保用户在 YAML 或 Bean 中显式声明的参数绝对生效
                return Ordered.LOWEST_PRECEDENCE;
            }
        };
    }
}
