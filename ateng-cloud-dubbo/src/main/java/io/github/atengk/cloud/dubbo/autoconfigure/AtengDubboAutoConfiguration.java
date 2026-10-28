package io.github.atengk.cloud.dubbo.autoconfigure;

import io.github.atengk.cloud.dubbo.overload.DubboOverloadMetrics;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.spring.context.config.DubboConfigBeanCustomizer;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.spring.boot.autoconfigure.DubboAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
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
@AutoConfigureBefore(DubboAutoConfiguration.class)
@ConditionalOnClass(AbstractConfig.class)
@EnableConfigurationProperties(AtengDubboProperties.class)
@ConditionalOnProperty(prefix = "ateng.cloud.dubbo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengDubboAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengDubboAutoConfiguration.class);

    public AtengDubboAutoConfiguration(AtengDubboProperties properties) {
        DubboPropertiesHolder.set(properties);
    }

    /**
     * 注册 Dubbo 配置对象定制器，为 Consumer、Provider、Protocol 与 Application 注入生产安全基线默认值
     *
     * @param properties Dubbo 配置属性
     * @return AtengDubboConfigBeanCustomizer 实例
     */
    @Bean
    @ConditionalOnMissingBean(AtengDubboConfigBeanCustomizer.class)
    public AtengDubboConfigBeanCustomizer atengDubboConfigBeanCustomizer(AtengDubboProperties properties) {
        return new AtengDubboConfigBeanCustomizer(properties);
    }

    /**
     * 框架定制的 Dubbo 配置对象定制器实现
     */
    public static class AtengDubboConfigBeanCustomizer implements DubboConfigBeanCustomizer, Ordered {

        private final AtengDubboProperties properties;

        public AtengDubboConfigBeanCustomizer(AtengDubboProperties properties) {
            this.properties = properties;
        }

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
                if (providerConfig.getValidation() == null && properties.getValidation() != null && properties.getValidation().isEnabled()) {
                    providerConfig.setValidation("true");
                }
                if (providerConfig.getThreadpool() == null && properties.getOverload() != null && properties.getOverload().isEnabled()) {
                    providerConfig.setThreadpool("overload");
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
                if (appProps != null) {
                    if (applicationConfig.getRegisterMode() == null) {
                        applicationConfig.setRegisterMode(appProps.getRegisterMode());
                    }
                    if (applicationConfig.getShutwait() == null && appProps.getShutWait() != null) {
                        applicationConfig.setShutwait(String.valueOf(appProps.getShutWait().toMillis()));
                    }
                }
            }
        }

        @Override
        public int getOrder() {
            // 最低优先级，确保用户在 YAML 或 Bean 中显式声明的参数绝对生效
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    /**
     * 注册 Dubbo 服务端线程池指标采集器，自动将 Dubbo 线程池状态与过载拒绝指标绑定至 Micrometer
     *
     * @return DubboOverloadMetrics 实例
     */
    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.cloud.dubbo.overload", name = "metrics-enabled", havingValue = "true", matchIfMissing = true)
    public DubboOverloadMetrics dubboOverloadMetrics() {
        return new DubboOverloadMetrics();
    }

    /**
     * 在容器单例装配完成后，确保 Dubbo 核心应用与通信层配置对齐生产级基线
     *
     * @param properties Dubbo 配置属性
     * @return SmartInitializingSingleton 实例
     */
    @Bean
    @ConditionalOnMissingBean(name = "atengDubboApplicationConfigCustomizer")
    public SmartInitializingSingleton atengDubboApplicationConfigCustomizer(AtengDubboProperties properties) {
        return () -> applyBaselineToApplicationModel(properties);
    }

    private void applyBaselineToApplicationModel(AtengDubboProperties properties) {
        try {
            var appProps = properties.getApplication();
            if (appProps != null && appProps.getShutWait() != null) {
                ApplicationModel.defaultModel()
                        .getApplicationConfigManager()
                        .getApplication()
                        .ifPresent(appConfig -> {
                            if (appConfig.getShutwait() == null) {
                                appConfig.setShutwait(String.valueOf(appProps.getShutWait().toMillis()));
                            }
                        });
            }
            if (properties.getPayload() != null) {
                int payloadBytes = (int) properties.getPayload().toBytes();
                ApplicationModel.defaultModel()
                        .getApplicationConfigManager()
                        .getDefaultProtocols()
                        .forEach(protocolConfig -> {
                            if (protocolConfig.getPayload() == null) {
                                protocolConfig.setPayload(payloadBytes);
                            }
                        });
                ApplicationModel.defaultModel()
                        .getDefaultModule()
                        .getConfigManager()
                        .getProviders()
                        .forEach(providerConfig -> {
                            if (providerConfig.getPayload() == null) {
                                providerConfig.setPayload(payloadBytes);
                            }
                        });
            }
        } catch (Exception ex) {
            log.debug("[DUBBO] 检查 Dubbo 运行时基线配置时捕获非关键异常，已安全跳过: {}", ex.getMessage());
        }
    }
}
