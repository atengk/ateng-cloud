package io.github.atengk.cloud.nacos.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.cloud.nacos.registry.NacosRegistrationCustomizer;
import io.github.atengk.cloud.nacos.actuator.NacosServiceEndpoint;
import io.github.atengk.cloud.nacos.filter.ZoneAffinityInstanceFilter;
import io.github.atengk.cloud.nacos.guard.SingletonImmutabilityGuard;
import io.github.atengk.cloud.nacos.lifecycle.NacosGracefulShutdownLifecycle;
import io.github.atengk.cloud.nacos.lifecycle.NacosLifecycleStateMachine;
import io.github.atengk.cloud.nacos.metadata.DefaultNacosMetadataProvider;
import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import io.github.atengk.cloud.nacos.metrics.NacosMetricsManager;
import io.github.atengk.cloud.nacos.network.NacosNetworkAddressDetector;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.refresh.NacosConfigRefreshEngine;
import io.github.atengk.cloud.nacos.spi.DefaultNacosMetadataCustomizer;
import io.github.atengk.cloud.nacos.spi.NacosConfigChangeListener;
import io.github.atengk.cloud.nacos.spi.NacosMetadataCustomizer;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.serviceregistry.AbstractAutoServiceRegistration;
import org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.List;

/**
 * Nacos 服务注册与配置中心基础设施自动装配入口
 *
 * <p>负责装配智能网卡侦测器、多维实例元数据提供者、注册元数据定制器、
 * 属性后置处理器、双阶段优雅下线生命周期及 Actuator 专属运维端点。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
@AutoConfiguration
@EnableConfigurationProperties(AtengNacosProperties.class)
@ConditionalOnClass(NacosDiscoveryProperties.class)
@ConditionalOnProperty(prefix = "ateng.cloud.nacos", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengNacosAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengNacosAutoConfiguration.class);

    public AtengNacosAutoConfiguration() {
        log.info("ateng-cloud Nacos infrastructure autoconfiguration activated.");
    }

    /**
     * 网卡智能侦测与精准 IP 选择器
     */
    @Bean
    @ConditionalOnMissingBean
    public NacosNetworkAddressDetector nacosNetworkAddressDetector() {
        return new NacosNetworkAddressDetector();
    }

    /**
     * 默认实例元数据定制器 SPI 保底实现
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultNacosMetadataCustomizer defaultNacosMetadataCustomizer(AtengNacosProperties properties) {
        return new DefaultNacosMetadataCustomizer(properties);
    }

    /**
     * 多维实例元数据提供者
     */
    @Bean
    @ConditionalOnMissingBean
    public NacosMetadataProvider nacosMetadataProvider(AtengNacosProperties properties,
                                                       Environment environment,
                                                       ObjectProvider<List<NacosMetadataCustomizer>> customizersProvider) {
        List<NacosMetadataCustomizer> customizers = customizersProvider.getIfAvailable();
        return new DefaultNacosMetadataProvider(properties, environment, customizers != null ? customizers : Collections.emptyList());
    }

    /**
     * NacosDiscoveryProperties 属性后置处理器，负责在实例注册前注入 IP 与基础元数据
     */
    @Bean
    @ConditionalOnClass(NacosDiscoveryProperties.class)
    @ConditionalOnMissingBean
    public static NacosDiscoveryPropertiesPostProcessor nacosDiscoveryPropertiesPostProcessor(
            ObjectProvider<AtengNacosProperties> propertiesProvider,
            ObjectProvider<NacosNetworkAddressDetector> networkAddressDetectorProvider,
            ObjectProvider<NacosMetadataProvider> metadataProvider,
            ObjectProvider<Environment> environmentProvider) {
        return new NacosDiscoveryPropertiesPostProcessor(propertiesProvider, networkAddressDetectorProvider, metadataProvider, environmentProvider);
    }

    /**
     * 实例注册定制器，在 NacosRegistration 初始化时填充元数据
     */
    @Bean
    @ConditionalOnClass(NacosRegistrationCustomizer.class)
    @ConditionalOnMissingBean
    public NacosRegistrationCustomizer atengNacosRegistrationCustomizer(NacosMetadataProvider metadataProvider) {
        return registration -> {
            log.debug("Injecting ateng-cloud instance metadata into NacosRegistration for service '{}'", registration.getServiceId());
            metadataProvider.populateMetadata(registration.getMetadata());
        };
    }

    /**
     * Nacos 实例生命周期状态机
     */
    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("rawtypes")
    public NacosLifecycleStateMachine nacosLifecycleStateMachine(
            AtengNacosProperties properties,
            ApplicationEventPublisher eventPublisher,
            ObjectProvider<AbstractAutoServiceRegistration> autoRegistrationProvider) {
        return new NacosLifecycleStateMachine(properties, eventPublisher, autoRegistrationProvider);
    }

    /**
     * Nacos 优雅下线最高优先级生命周期钩子
     */
    @Bean
    @ConditionalOnMissingBean
    public NacosGracefulShutdownLifecycle nacosGracefulShutdownLifecycle(NacosLifecycleStateMachine stateMachine) {
        return new NacosGracefulShutdownLifecycle(stateMachine);
    }

    /**
     * Actuator 专属运维与生命周期端点 (/actuator/nacos-service)
     */
    @Bean
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.nacos.endpoint", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public NacosServiceEndpoint nacosServiceEndpoint(NacosLifecycleStateMachine stateMachine,
                                                     NacosMetadataProvider metadataProvider,
                                                     AtengNacosProperties properties,
                                                     Environment environment,
                                                     ObjectProvider<NacosConfigRefreshEngine> refreshEngineProvider) {
        return new NacosServiceEndpoint(stateMachine, metadataProvider, properties, environment, refreshEngineProvider.getIfAvailable());
    }

    /**
     * 同机房 (Zone) 就近亲和过滤器
     */
    @Bean
    @ConditionalOnMissingBean
    public ZoneAffinityInstanceFilter zoneAffinityInstanceFilter() {
        return new ZoneAffinityInstanceFilter();
    }

    /**
     * Nacos 统一运维抽象门面
     */
    @Bean
    @ConditionalOnMissingBean
    public NacosTemplate nacosTemplate(ObjectProvider<NacosServiceManager> serviceManagerProvider,
                                       ObjectProvider<NacosDiscoveryProperties> discoveryPropertiesProvider,
                                       ObjectProvider<NacosConfigManager> configManagerProvider,
                                       ObjectProvider<NacosConfigRefreshEngine> refreshEngineProvider,
                                       AtengNacosProperties properties,
                                       NacosMetadataProvider metadataProvider,
                                       ObjectProvider<NacosLifecycleStateMachine> stateMachineProvider,
                                       ZoneAffinityInstanceFilter zoneAffinityFilter) {
        return new NacosTemplate(serviceManagerProvider, discoveryPropertiesProvider, configManagerProvider,
                refreshEngineProvider, properties, metadataProvider, stateMachineProvider, zoneAffinityFilter);
    }

    /**
     * Nacos 现代配置中心启动健康自检与诊断器
     */
    @Bean
    @ConditionalOnMissingBean
    public NacosConfigHealthChecker nacosConfigHealthChecker(
            AtengNacosProperties properties,
            Environment environment,
            ObjectProvider<NacosConfigManager> configManagerProvider) {
        return new NacosConfigHealthChecker(properties, environment, configManagerProvider);
    }

    /**
     * Nacos Micrometer 全维监控标尺管理器
     */
    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.nacos.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public NacosMetricsManager nacosMetricsManager(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<NacosTemplate> nacosTemplateProvider,
            ObjectProvider<NacosLifecycleStateMachine> stateMachineProvider,
            Environment environment) {
        return new NacosMetricsManager(meterRegistryProvider.getIfAvailable(), nacosTemplateProvider, stateMachineProvider, environment);
    }

    /**
     * Nacos 安全配置热更核心引擎
     */
    @Bean
    @ConditionalOnMissingBean
    public NacosConfigRefreshEngine nacosConfigRefreshEngine(
            Environment environment,
            ApplicationEventPublisher eventPublisher,
            ObjectProvider<List<NacosConfigChangeListener>> listenersProvider,
            ObjectProvider<ConfigurationPropertiesRebinder> rebinderProvider,
            AtengNacosProperties properties,
            ObjectProvider<NacosMetricsManager> metricsManagerProvider) {
        return new NacosConfigRefreshEngine(environment, eventPublisher, listenersProvider, rebinderProvider, properties, metricsManagerProvider);
    }

    /**
     * 业务单例不可变性架构守卫 (Bean Immutability Guard)
     */
    @Bean
    @ConditionalOnMissingBean
    public static SingletonImmutabilityGuard singletonImmutabilityGuard(Environment environment) {
        return new SingletonImmutabilityGuard(environment);
    }
}
