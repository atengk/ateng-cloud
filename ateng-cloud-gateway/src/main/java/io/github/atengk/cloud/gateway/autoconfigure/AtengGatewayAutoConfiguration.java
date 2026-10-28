package io.github.atengk.cloud.gateway.autoconfigure;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.filter.GatewayClientIpFilter;
import io.github.atengk.cloud.gateway.filter.GatewayHeaderSanitizeFilter;
import io.github.atengk.cloud.gateway.filter.GatewayIpFilter;
import io.github.atengk.cloud.gateway.filter.GatewayPathValidationFilter;
import io.github.atengk.cloud.gateway.filter.GatewayTenantFilter;
import io.github.atengk.cloud.gateway.filter.GatewayTraceFilter;
import io.github.atengk.cloud.gateway.filter.SaTokenGatewayFilter;
import io.github.atengk.cloud.gateway.handler.GatewayGlobalExceptionHandler;
import io.github.atengk.cloud.gateway.properties.AtengGatewayProperties;
import io.github.atengk.cloud.gateway.provider.PropertiesIpFilterProvider;
import io.github.atengk.cloud.gateway.route.NacosRouteDefinitionRepository;
import io.github.atengk.cloud.gateway.spi.IpFilterProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.codec.CodecCustomizer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ateng-cloud 响应式网关自动装配核心配置类
 *
 * @author Ateng
 * @since 2026-10-28
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties(AtengGatewayProperties.class)
@ConditionalOnProperty(prefix = "ateng.cloud.gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengGatewayAutoConfiguration {

    /**
     * 注册全局响应式统一异常处理器
     *
     * @param objectMapper Jackson 对象映射器
     * @return GatewayGlobalExceptionHandler
     */
    @Bean
    @ConditionalOnMissingBean
    public GatewayGlobalExceptionHandler gatewayGlobalExceptionHandler(ObjectMapper objectMapper) {
        return new GatewayGlobalExceptionHandler(objectMapper);
    }

    /**
     * 定制 WebFlux 编解码器最大直接内存上限 (载荷防爆硬防线)
     *
     * @param properties 网关配置属性
     * @return CodecCustomizer
     */
    @Bean
    @ConditionalOnMissingBean
    public CodecCustomizer atengGatewayCodecCustomizer(AtengGatewayProperties properties) {
        return configurer -> configurer.defaultCodecs()
                .maxInMemorySize((int) properties.getMaxInMemorySize().toBytes());
    }

    /**
     * 网关边缘安全防卫与过滤器自动装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.security", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class SecurityFilterAutoConfiguration {

        /**
         * 注册 URI 规范化与目录穿越防卫全局过滤器
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ateng.cloud.gateway.security", name = "path-validation-enabled", havingValue = "true", matchIfMissing = true)
        public GatewayPathValidationFilter gatewayPathValidationFilter() {
            return new GatewayPathValidationFilter();
        }

        /**
         * 注册响应式无锁链路追踪 Trace 全局过滤器
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ateng.cloud.gateway.security", name = "trace-enabled", havingValue = "true", matchIfMissing = true)
        public GatewayTraceFilter gatewayTraceFilter() {
            return new GatewayTraceFilter();
        }

        /**
         * 注册入站敏感标头物理剥离清洗全局过滤器
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ateng.cloud.gateway.security", name = "header-sanitize-enabled", havingValue = "true", matchIfMissing = true)
        public GatewayHeaderSanitizeFilter gatewayHeaderSanitizeFilter(AtengGatewayProperties properties) {
            return new GatewayHeaderSanitizeFilter(properties.getSecurity());
        }

        /**
         * 注册真实公网客户端 IP 解析全局过滤器
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ateng.cloud.gateway.security", name = "client-ip-enabled", havingValue = "true", matchIfMissing = true)
        public GatewayClientIpFilter gatewayClientIpFilter(AtengGatewayProperties properties) {
            return new GatewayClientIpFilter(properties.getSecurity());
        }

        /**
         * 注册默认 IP 黑白名单规则提供者 (读取配置属性)
         */
        @Bean
        @ConditionalOnMissingBean(IpFilterProvider.class)
        public IpFilterProvider ipFilterProvider(AtengGatewayProperties properties) {
            return new PropertiesIpFilterProvider(properties.getSecurity());
        }

        /**
         * 注册 IP 黑白名单防卫全局过滤器
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ateng.cloud.gateway.security", name = "ip-filter-enabled", havingValue = "true", matchIfMissing = true)
        public GatewayIpFilter gatewayIpFilter(IpFilterProvider ipFilterProvider) {
            return new GatewayIpFilter(ipFilterProvider);
        }

        /**
         * 注册 Sa-Token 反应式微服务网关集中安全鉴权与零信任防御全局过滤器
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ateng.cloud.gateway.security", name = "sa-token-enabled", havingValue = "true", matchIfMissing = true)
        public SaTokenGatewayFilter saTokenGatewayFilter(AtengGatewayProperties properties) {
            return new SaTokenGatewayFilter(properties.getSecurity());
        }

        /**
         * 注册多租户入站解析与防跨越隔离全局过滤器
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ateng.cloud.gateway.tenant", name = "enabled", havingValue = "true", matchIfMissing = true)
        public GatewayTenantFilter gatewayTenantFilter(AtengGatewayProperties properties) {
            return new GatewayTenantFilter(properties.getTenant());
        }
    }

    /**
     * Nacos 动态路由持久化与无感原子热更自动装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(NacosConfigManager.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.dynamic-route", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class DynamicRouteAutoConfiguration {

        /**
         * 注册基于 Nacos 配置中心的动态路由仓库实现
         */
        @Bean
        @ConditionalOnMissingBean
        public NacosRouteDefinitionRepository nacosRouteDefinitionRepository(
                ObjectProvider<NacosConfigManager> nacosConfigManagerProvider,
                AtengGatewayProperties properties,
                ObjectMapper objectMapper,
                ApplicationEventPublisher applicationEventPublisher) {
            return new NacosRouteDefinitionRepository(
                    nacosConfigManagerProvider,
                    properties.getDynamicRoute(),
                    objectMapper,
                    applicationEventPublisher);
        }
    }
}
