package io.github.atengk.cloud.traffic.loadbalancer;

import io.github.atengk.cloud.nacos.loadbalancer.NacosWarmupLoadBalancer;
import io.github.atengk.cloud.nacos.loadbalancer.ZonePreferenceServiceInstanceListSupplier;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.loadbalancer.cache.LoadBalancerCacheProperties;
import org.springframework.cloud.loadbalancer.config.LoadBalancerZoneConfig;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplierBuilder;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 微服务全链路流量治理三层复合负载均衡客户端子上下文配置
 *
 * <p>核心治理语义与拓扑顺序不变量：
 * 1. 复合责任链严格按：{@code DiscoveryClient} ➔ {@code Caching} (3s) ➔ {@code TrafficTag} ➔ {@code ZonePreference} ➔ {@code NacosWarmupLoadBalancer} 嵌套；
 * 2. 调度优先级：业务灰度版本隔离级 优先于 拓扑机房亲和级，拓扑亲和级 优先于 实例预热平滑加权级；
 * 3. 默认将客户端本地实例缓存 TTL 从原生 35s 收敛至 3s，消除滚动发布与优雅下线时的盲区；
 * 4. 采用内部静态辅助类安全隔离 Nacos 可选依赖，无 Nacos 依赖时平滑退化且零 ClassLoading 异常。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Configuration(proxyBeanMethods = false)
public class AtengTrafficLoadBalancerClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengTrafficLoadBalancerClientConfiguration.class);

    private static final boolean NACOS_PRESENT = ClassUtils.isPresent(
            "io.github.atengk.cloud.nacos.loadbalancer.ZonePreferenceServiceInstanceListSupplier",
            AtengTrafficLoadBalancerClientConfiguration.class.getClassLoader()
    );

    private static final boolean NACOS_WARMUP_PRESENT = ClassUtils.isPresent(
            "io.github.atengk.cloud.nacos.loadbalancer.NacosWarmupLoadBalancer",
            AtengTrafficLoadBalancerClientConfiguration.class.getClassLoader()
    );

    @Bean
    @org.springframework.context.annotation.Primary
    @ConditionalOnMissingBean
    public ServiceInstanceListSupplier serviceInstanceListSupplier(
            ConfigurableApplicationContext context,
            ObjectProvider<AtengTrafficProperties> propertiesProvider) {
        AtengTrafficProperties trafficProperties = propertiesProvider.getIfAvailable();
        if (trafficProperties == null) {
            trafficProperties = new AtengTrafficProperties();
        }

        // 1. 底层服务发现与可选缓存层 (默认 TTL=3s)
        ServiceInstanceListSupplierBuilder builder = ServiceInstanceListSupplier.builder();
        if (hasReactiveDiscoveryClient(context)) {
            builder.withDiscoveryClient();
        } else {
            builder.withBlockingDiscoveryClient();
        }
        boolean cacheEnabled = context.getEnvironment().getProperty("spring.cloud.loadbalancer.cache.enabled", Boolean.class, true);
        if (cacheEnabled) {
            builder.withCaching();
        }
        ServiceInstanceListSupplier cachingSupplier = builder.build(context);

        // 2. 第一层：业务灰度版本隔离 (按 X-Traffic-Tag 锁定版本池，未染色走基线，全下线平滑降级)
        ServiceInstanceListSupplier trafficSupplier = new TrafficTagServiceInstanceListSupplier(cachingSupplier, trafficProperties);

        // 3. 第二层：拓扑机房亲和级 (在版本隔离池内优先筛选同机房健康实例，本机房全宕平滑跨区容灾)
        return decorateWithZonePreference(trafficSupplier, context);
    }

    private boolean hasReactiveDiscoveryClient(ConfigurableApplicationContext context) {
        try {
            String[] names = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
                    context, ReactiveDiscoveryClient.class);
            return names != null && names.length > 0;
        } catch (Throwable ex) {
            log.trace("[ateng-cloud-traffic] Probing ReactiveDiscoveryClient failed, falling back to false: {}", ex.getMessage());
            return false;
        }
    }

    private ServiceInstanceListSupplier decorateWithZonePreference(
            ServiceInstanceListSupplier delegate,
            ConfigurableApplicationContext context) {
        if (NACOS_PRESENT) {
            return NacosZonePreferenceSupplierHelper.wrap(delegate, context);
        }
        LoadBalancerZoneConfig zoneConfig = context.getBeanProvider(LoadBalancerZoneConfig.class).getIfAvailable();
        if (zoneConfig != null && StringUtils.hasText(zoneConfig.getZone())) {
            return new org.springframework.cloud.loadbalancer.core.ZonePreferenceServiceInstanceListSupplier(delegate, zoneConfig);
        }
        return delegate;
    }

    /**
     * 第三层：实例选择与平滑预热加权轮询
     */
    @Bean
    @ConditionalOnMissingBean
    public ReactorLoadBalancer<ServiceInstance> reactorLoadBalancer(
            ConfigurableApplicationContext context,
            Environment environment,
            LoadBalancerClientFactory loadBalancerClientFactory) {
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        if (NACOS_WARMUP_PRESENT) {
            log.debug("[ateng-cloud-traffic] 接入 NacosWarmupLoadBalancer 平滑预热加权选择器 [serviceId={}]", name);
            return NacosWarmupLoadBalancerHelper.create(
                    name,
                    loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class),
                    context
            );
        }
        return new RoundRobinLoadBalancer(
                loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class),
                name
        );
    }

    /**
     * 注册客户端负载均衡本地缓存时效属性，默认将实例缓存 TTL 收敛至 3 秒
     */
    @Bean
    @ConditionalOnMissingBean
    public LoadBalancerCacheProperties loadBalancerCacheProperties() {
        LoadBalancerCacheProperties cacheProperties = new LoadBalancerCacheProperties();
        cacheProperties.setTtl(Duration.ofSeconds(3));
        return cacheProperties;
    }

    private static class NacosZonePreferenceSupplierHelper {
        static ServiceInstanceListSupplier wrap(ServiceInstanceListSupplier delegate,
                                                ConfigurableApplicationContext context) {
            AtengNacosProperties nacosProps = context.getBeanProvider(AtengNacosProperties.class).getIfAvailable();
            return new ZonePreferenceServiceInstanceListSupplier(delegate, nacosProps, context.getEnvironment());
        }
    }

    private static class NacosWarmupLoadBalancerHelper {
        static ReactorLoadBalancer<ServiceInstance> create(
                String serviceId,
                ObjectProvider<ServiceInstanceListSupplier> supplierProvider,
                ConfigurableApplicationContext context) {
            AtengNacosProperties nacosProps = context.getBeanProvider(AtengNacosProperties.class).getIfAvailable();
            ObjectProvider<InstanceWeightCalculator> weightCalculatorProvider = context.getBeanProvider(InstanceWeightCalculator.class);
            return new NacosWarmupLoadBalancer(serviceId, supplierProvider, nacosProps, weightCalculatorProvider);
        }
    }
}
