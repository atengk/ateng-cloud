package io.github.atengk.cloud.dubbo.autoconfigure;

import io.github.atengk.cloud.dubbo.overload.DubboOverloadMetrics;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dubbo 自动装配白盒单元测试 (验证装配条件、配置属性绑定、定制器生效与缺省覆盖)
 *
 * @author Ateng
 * @since 2026-10-31
 */
class AtengDubboAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengDubboAutoConfiguration.class));

    @Test
    @DisplayName("默认配置下成功加载核心 Bean 与基线定制器")
    void testDefaultAutoConfiguration() {
        contextRunner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengDubboAutoConfiguration.class);
                    assertThat(context).hasSingleBean(AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer.class);
                    assertThat(context).hasSingleBean(DubboOverloadMetrics.class);
                    assertThat(context).hasBean("atengDubboApplicationConfigCustomizer");

                    AtengDubboProperties properties = context.getBean(AtengDubboProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getRetries()).isEqualTo(0);
                    assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.getPayload()).isEqualTo(DataSize.ofMegabytes(16));
                });
    }

    @Test
    @DisplayName("配置 ateng.cloud.dubbo.enabled=false 时安全短路，不加载任何 Dubbo 增强 Bean")
    void testDisabledAutoConfiguration() {
        contextRunner
                .withPropertyValues("ateng.cloud.dubbo.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengDubboAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer.class);
                    assertThat(context).doesNotHaveBean(DubboOverloadMetrics.class);
                });
    }

    @Test
    @DisplayName("配置 ateng.cloud.dubbo.overload.metrics-enabled=false 时关闭指标采集器")
    void testDisabledMetrics() {
        contextRunner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("ateng.cloud.dubbo.overload.metrics-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengDubboAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(DubboOverloadMetrics.class);
                });
    }

    @Test
    @DisplayName("支持下游工程通过 @Bean 自定义扩展并覆盖默认 Customizer")
    void testConditionalOnMissingBeanCustomizer() {
        contextRunner
                .withUserConfiguration(CustomCustomizerConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer.class);
                    AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer customizer =
                            context.getBean(AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer.class);
                    assertThat(customizer).isSameAs(CustomCustomizerConfig.CUSTOM_INSTANCE);
                });
    }

    @Test
    @DisplayName("AtengDubboConfigBeanCustomizer: ConsumerConfig 属性注入与不变量守护")
    void testCustomizeConsumerConfig() {
        AtengDubboProperties props = new AtengDubboProperties();
        props.setRetries(2);
        props.setTimeout(Duration.ofSeconds(5));

        AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer customizer =
                new AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer(props);
        assertThat(customizer.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);

        // 1. 缺省时注入框架基线值
        ConsumerConfig consumerConfig = new ConsumerConfig();
        customizer.customize("consumerConfig", consumerConfig);
        assertThat(consumerConfig.getRetries()).isEqualTo(2);
        assertThat(consumerConfig.getTimeout()).isEqualTo(5000);

        // 2. 用户显式设置时不予覆盖
        ConsumerConfig userConsumerConfig = new ConsumerConfig();
        userConsumerConfig.setRetries(5);
        userConsumerConfig.setTimeout(10000);
        customizer.customize("userConsumerConfig", userConsumerConfig);
        assertThat(userConsumerConfig.getRetries()).isEqualTo(5);
        assertThat(userConsumerConfig.getTimeout()).isEqualTo(10000);
    }

    @Test
    @DisplayName("AtengDubboConfigBeanCustomizer: ProviderConfig 属性注入与过载线程池绑定")
    void testCustomizeProviderConfig() {
        AtengDubboProperties props = new AtengDubboProperties();
        props.setRetries(1);
        props.setTimeout(Duration.ofSeconds(6));
        props.setPayload(DataSize.ofMegabytes(10));
        props.getValidation().setEnabled(true);
        props.getOverload().setEnabled(true);

        AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer customizer =
                new AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer(props);

        // 1. 缺省注入
        ProviderConfig providerConfig = new ProviderConfig();
        customizer.customize("providerConfig", providerConfig);
        assertThat(providerConfig.getRetries()).isEqualTo(1);
        assertThat(providerConfig.getTimeout()).isEqualTo(6000);
        assertThat(providerConfig.getPayload()).isEqualTo((int) DataSize.ofMegabytes(10).toBytes());
        assertThat(providerConfig.getValidation()).isEqualTo("true");
        assertThat(providerConfig.getThreadpool()).isEqualTo("overload");

        // 2. 用户显式设置不予覆盖
        ProviderConfig userProviderConfig = new ProviderConfig();
        userProviderConfig.setValidation("customValidation");
        userProviderConfig.setThreadpool("fixed");
        customizer.customize("userProviderConfig", userProviderConfig);
        assertThat(userProviderConfig.getValidation()).isEqualTo("customValidation");
        assertThat(userProviderConfig.getThreadpool()).isEqualTo("fixed");
    }

    @Test
    @DisplayName("AtengDubboConfigBeanCustomizer: ProtocolConfig 与 ApplicationConfig 属性注入")
    void testCustomizeProtocolAndApplicationConfig() {
        AtengDubboProperties props = new AtengDubboProperties();
        props.getProtocol().setName("tri");
        props.getProtocol().setPort(50051);
        props.getProtocol().setSerialization("fastjson2");
        props.getApplication().setRegisterMode("instance");
        props.getApplication().setShutWait(Duration.ofSeconds(20));

        AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer customizer =
                new AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer(props);

        ProtocolConfig protocolConfig = new ProtocolConfig();
        customizer.customize("protocolConfig", protocolConfig);
        assertThat(protocolConfig.getName()).isEqualTo("tri");
        assertThat(protocolConfig.getPort()).isEqualTo(50051);
        assertThat(protocolConfig.getSerialization()).isEqualTo("fastjson2");

        ApplicationConfig applicationConfig = new ApplicationConfig();
        customizer.customize("applicationConfig", applicationConfig);
        assertThat(applicationConfig.getRegisterMode()).isEqualTo("instance");
        assertThat(applicationConfig.getShutwait()).isEqualTo("20000");
    }

    @Test
    @DisplayName("验证 SmartInitializingSingleton 运行与反序列化白名单安全加载")
    void testSmartInitializingSingleton() {
        AtengDubboProperties props = new AtengDubboProperties();
        props.getSecurity().getSerialize().setAllowPackages(Collections.singletonList("io.github.atengk.cloud.demo"));

        AtengDubboAutoConfiguration autoConfig = new AtengDubboAutoConfiguration(props);
        SmartInitializingSingleton singleton = autoConfig.atengDubboApplicationConfigCustomizer(props);
        assertThat(singleton).isNotNull();
        // 执行应安全自洽，不抛出异常
        singleton.afterSingletonsInstantiated();
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomCustomizerConfig {
        static final AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer CUSTOM_INSTANCE =
                new AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer(new AtengDubboProperties());

        @Bean
        AtengDubboAutoConfiguration.AtengDubboConfigBeanCustomizer atengDubboConfigBeanCustomizer() {
            return CUSTOM_INSTANCE;
        }
    }
}
