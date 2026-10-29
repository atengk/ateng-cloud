package io.github.atengk.cloud.gateway.loadbalancer;

import io.github.atengk.cloud.gateway.properties.AtengGatewayProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
import io.github.atengk.cloud.nacos.loadbalancer.NacosWarmupLoadBalancer;
import io.github.atengk.cloud.nacos.loadbalancer.ZonePreferenceServiceInstanceListSupplier;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 响应式网关复合负载均衡责任链与客户端子上下文装配配置
 *
 * <p>核心治理语义与拓扑顺序不变量：
 * 1. 消除与 Nacos 模块在 Spring Cloud LoadBalancer 客户端子上下文中的 Bean 竞争；
 * 2. 复合责任链严格按：{@code DiscoveryClient} ➔ {@code Caching} (3s) ➔ {@code Gray} ➔ {@code ZonePreference} ➔ {@code NacosWarmupLoadBalancer} 嵌套；
 * 3. 业务隔离级优先于拓扑亲和级：先按流量染色标签锁定目标版本池，再在目标版本池内部优先筛选同机房健康可用节点，本区全宕平滑跨区容灾；
 * 4. 默认将网关实例缓存 TTL 从 35s 收敛至 3s，消除滚动发布与优雅下线时的盲区；
 * 5. 采用内部辅助类安全隔离可选依赖，确保无 Nacos 依赖时平滑退化且零 ClassLoading 异常。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Configuration(proxyBeanMethods = false)
public class GrayLoadBalancerClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GrayLoadBalancerClientConfiguration.class);

    private static final boolean NACOS_PRESENT = ClassUtils.isPresent(
            "io.github.atengk.cloud.nacos.loadbalancer.ZonePreferenceServiceInstanceListSupplier",
            GrayLoadBalancerClientConfiguration.class.getClassLoader()
    );

    private static final boolean NACOS_WARMUP_PRESENT = ClassUtils.isPresent(
            "io.github.atengk.cloud.nacos.loadbalancer.NacosWarmupLoadBalancer",
            GrayLoadBalancerClientConfiguration.class.getClassLoader()
    );

    @Bean
    @ConditionalOnMissingBean
    public ServiceInstanceListSupplier serviceInstanceListSupplier(
            ConfigurableApplicationContext context,
            ObjectProvider<AtengGatewayProperties> propertiesProvider) {
        AtengGatewayTrafficProperties trafficProperties = propertiesProvider.getIfAvailable() != null
                ? propertiesProvider.getIfAvailable().getTraffic()
                : new AtengGatewayTrafficProperties();

        // 1. 底层发现与缓存层：构建 DiscoveryClient 并挂载本地缓存 (默认 TTL=3s)
        ServiceInstanceListSupplierBuilder builder = ServiceInstanceListSupplier.builder();
        if (hasReactiveDiscoveryClient(context)) {
            builder.withDiscoveryClient();
        } else {
            builder.withBlockingDiscoveryClient();
        }
        ServiceInstanceListSupplier cachingSupplier = builder
                .withCaching()
                .build(context);

        // 2. 业务隔离级：金丝雀灰度过滤 (按 X-Traffic-Tag 锁定版本池，未染色流量走基线，灰度全下线降级)
        ServiceInstanceListSupplier graySupplier = new GrayServiceInstanceListSupplier(cachingSupplier, trafficProperties);

        // 3. 拓扑亲和级：在版本隔离池内优先筛选同机房健康实例，本机房全宕平滑跨区容灾
        return decorateWithZonePreference(graySupplier, context);
    }

    private boolean hasReactiveDiscoveryClient(ConfigurableApplicationContext context) {
        try {
            return context.getBeanProvider(ReactiveDiscoveryClient.class).getIfAvailable() != null;
        } catch (Throwable ignored) {
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

    @Bean
    @ConditionalOnMissingBean
    public ReactorLoadBalancer<ServiceInstance> reactorLoadBalancer(
            ConfigurableApplicationContext context,
            Environment environment,
            LoadBalancerClientFactory loadBalancerClientFactory) {
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        if (NACOS_WARMUP_PRESENT) {
            log.debug("[ateng-cloud-gateway] 接入 NacosWarmupLoadBalancer 平滑预热与自适应加权选择器 [serviceId={}]", name);
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
     * 注册网关负载均衡本地缓存属性，默认将实例缓存 TTL 收敛至 3s
     */
    @Bean
    @ConditionalOnMissingBean
    public LoadBalancerCacheProperties loadBalancerCacheProperties(
            ObjectProvider<AtengGatewayProperties> propertiesProvider) {
        LoadBalancerCacheProperties cacheProperties = new LoadBalancerCacheProperties();
        Duration ttl = Duration.ofSeconds(3);
        if (propertiesProvider.getIfAvailable() != null
                && propertiesProvider.getIfAvailable().getTraffic() != null
                && propertiesProvider.getIfAvailable().getTraffic().getCacheTtl() != null) {
            ttl = propertiesProvider.getIfAvailable().getTraffic().getCacheTtl();
        }
        cacheProperties.setTtl(ttl);
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
