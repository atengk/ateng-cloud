package io.github.atengk.cloud.gateway.autoconfigure;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.filter.GatewayAccessLogFilter;
import io.github.atengk.cloud.gateway.filter.GatewayClientIpFilter;
import io.github.atengk.cloud.gateway.filter.GatewayHeaderSanitizeFilter;
import io.github.atengk.cloud.gateway.filter.GatewayIpFilter;
import io.github.atengk.cloud.gateway.filter.GatewayPathValidationFilter;
import io.github.atengk.cloud.gateway.filter.GatewayRateLimitResponseFilter;
import io.github.atengk.cloud.gateway.filter.GatewaySecurityHeadersFilter;
import io.github.atengk.cloud.gateway.filter.GatewaySignGuardFilter;
import io.github.atengk.cloud.gateway.filter.GatewayTenantFilter;
import io.github.atengk.cloud.gateway.filter.GatewayTraceFilter;
import io.github.atengk.cloud.gateway.filter.GatewayTrafficTagFilter;
import io.github.atengk.cloud.gateway.filter.OpenApiGuardFilter;
import io.github.atengk.cloud.gateway.filter.SaTokenGatewayFilter;
import io.github.atengk.cloud.gateway.filter.factory.AtengRetryGatewayFilterFactory;
import io.github.atengk.cloud.gateway.handler.GatewayGlobalExceptionHandler;
import io.github.atengk.cloud.gateway.loadbalancer.GrayLoadBalancerClientConfiguration;
import io.github.atengk.cloud.gateway.properties.AtengGatewayProperties;
import io.github.atengk.cloud.gateway.provider.InMemoryGatewayNonceValidator;
import io.github.atengk.cloud.gateway.provider.InMemorySignSecretProvider;
import io.github.atengk.cloud.gateway.provider.PropertiesIpFilterProvider;
import io.github.atengk.cloud.gateway.ratelimit.ApiKeyResolver;
import io.github.atengk.cloud.gateway.ratelimit.IpKeyResolver;
import io.github.atengk.cloud.gateway.ratelimit.UserKeyResolver;
import io.github.atengk.cloud.gateway.resolver.DefaultTrafficTagResolver;
import io.github.atengk.cloud.gateway.route.GatewayRouteRefreshListener;
import io.github.atengk.cloud.gateway.route.NacosRouteDefinitionRepository;
import io.github.atengk.cloud.gateway.spi.GatewayNonceValidator;
import io.github.atengk.cloud.gateway.spi.IpFilterProvider;
import io.github.atengk.cloud.gateway.spi.SignSecretProvider;
import io.github.atengk.cloud.gateway.spi.TrafficTagResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.codec.CodecCustomizer;
import org.springframework.cloud.gateway.config.GatewayAutoConfiguration;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * ateng-cloud 响应式网关自动装配核心配置类
 *
 * @author Ateng
 * @since 2026-10-28
 */
@AutoConfiguration
@AutoConfigureBefore(GatewayAutoConfiguration.class)
@ConditionalOnClass(GlobalFilter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties(AtengGatewayProperties.class)
@ConditionalOnProperty(prefix = "ateng.cloud.gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
@LoadBalancerClients(defaultConfiguration = GrayLoadBalancerClientConfiguration.class)
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
     * 注册 Netty 响应式 HTTP 客户端生产级双阶超时定制器 (connectTimeout + responseTimeout)
     *
     * @param properties 网关配置属性
     * @return HttpClientCustomizer
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(HttpClientCustomizer.class)
    public HttpClientCustomizer gatewayHttpClientCustomizer(AtengGatewayProperties properties) {
        return httpClient -> {
            reactor.netty.http.client.HttpClient client = httpClient;
            if (properties.getConnectTimeout() != null) {
                client = client.option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) properties.getConnectTimeout().toMillis());
            }
            if (properties.getResponseTimeout() != null) {
                client = client.responseTimeout(properties.getResponseTimeout());
            }
            return client;
        };
    }

    /**
     * 注册弹性防雪崩安全重试过滤器工厂 (写请求零重试，读请求建连失败弹性重试 1 次)
     *
     * @return AtengRetryGatewayFilterFactory
     */
    @Bean
    @ConditionalOnMissingBean
    public AtengRetryGatewayFilterFactory atengRetryGatewayFilterFactory() {
        return new AtengRetryGatewayFilterFactory();
    }

    /**
     * 注册默认全链路灰度染色标签解析器 (Header > Query)
     *
     * @return TrafficTagResolver
     */
    @Bean
    @ConditionalOnMissingBean(TrafficTagResolver.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.traffic", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TrafficTagResolver defaultTrafficTagResolver() {
        return new DefaultTrafficTagResolver();
    }

    /**
     * 注册全链路灰度染色标签解析全局过滤器
     *
     * @param properties 网关配置属性
     * @param trafficTagResolver 流量染色标签解析器
     * @return GatewayTrafficTagFilter
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.traffic", name = "enabled", havingValue = "true", matchIfMissing = true)
    public GatewayTrafficTagFilter gatewayTrafficTagFilter(AtengGatewayProperties properties,
                                                           TrafficTagResolver trafficTagResolver) {
        return new GatewayTrafficTagFilter(properties.getTraffic(), trafficTagResolver);
    }

    /**
     * 注册 OWASP 等保三级合规安全响应头强化与跨域出站标头去重全局过滤器
     *
     * @param properties 网关配置属性
     * @return GatewaySecurityHeadersFilter
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.security-headers", name = "enabled", havingValue = "true", matchIfMissing = true)
    public GatewaySecurityHeadersFilter gatewaySecurityHeadersFilter(AtengGatewayProperties properties) {
        return new GatewaySecurityHeadersFilter(properties.getSecurityHeaders());
    }

    /**
     * 注册微服务 OpenAPI / Swagger 统一接口文档访问安全守卫全局过滤器
     *
     * @param properties 网关配置属性
     * @param objectMapper Jackson 对象映射器
     * @return OpenApiGuardFilter
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OpenApiGuardFilter openApiGuardFilter(AtengGatewayProperties properties, ObjectMapper objectMapper) {
        return new OpenApiGuardFilter(properties.getOpenapi(), objectMapper);
    }

    /**
     * 注册轻量级响应式访问日志审计与慢请求度量告警全局过滤器
     *
     * @param properties 网关配置属性
     * @return GatewayAccessLogFilter
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.access-log", name = "enabled", havingValue = "true", matchIfMissing = false)
    public GatewayAccessLogFilter gatewayAccessLogFilter(AtengGatewayProperties properties) {
        return new GatewayAccessLogFilter(properties.getAccessLog());
    }

    /**
     * 注册开放接口数字签名密钥提供者默认实现 (基于内存字典)
     *
     * @return InMemorySignSecretProvider
     */
    @Bean
    @ConditionalOnMissingBean(SignSecretProvider.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.sign", name = "enabled", havingValue = "true", matchIfMissing = false)
    public SignSecretProvider defaultSignSecretProvider() {
        return new InMemorySignSecretProvider();
    }

    /**
     * 注册开放接口 Nonce 防重放校验器默认实现 (基于内存滑动窗口)
     *
     * @return GatewayNonceValidator
     */
    @Bean
    @ConditionalOnMissingBean(GatewayNonceValidator.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.sign", name = "enabled", havingValue = "true", matchIfMissing = false)
    public GatewayNonceValidator defaultGatewayNonceValidator() {
        return new InMemoryGatewayNonceValidator();
    }

    /**
     * 注册开放接口 API 签名防篡改与 Nonce 防重放全局过滤器
     *
     * @param properties 网关配置属性
     * @param secretProvider 签名密钥提供者
     * @param nonceValidator Nonce 校验器
     * @param objectMapper Jackson 对象映射器
     * @return GatewaySignGuardFilter
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.sign", name = "enabled", havingValue = "true", matchIfMissing = false)
    public GatewaySignGuardFilter gatewaySignGuardFilter(AtengGatewayProperties properties,
                                                        SignSecretProvider secretProvider,
                                                        GatewayNonceValidator nonceValidator,
                                                        ObjectMapper objectMapper) {
        return new GatewaySignGuardFilter(properties.getSign(), secretProvider, nonceValidator, objectMapper);
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
            return new GatewayHeaderSanitizeFilter(properties.getSecurity(), properties.getTenant());
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
            // 定制 Sa-Token 防火墙检测失败处理逻辑，转译为统一 HTTP 400 Bad Request
            cn.dev33.satoken.strategy.SaFirewallStrategy.instance.checkFailHandle = (e, req, res, ext) -> {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "非法请求路径：检测到目录穿越或畸形字符", e);
            };
            return new SaTokenGatewayFilter(properties.getSecurity());
        }
    }

    /**
     * 多租户入站解析与防跨越隔离自动装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.tenant", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class TenantFilterAutoConfiguration {

        /**
         * 注册多租户入站解析与防跨越隔离全局过滤器
         */
        @Bean
        @ConditionalOnMissingBean
        public GatewayTenantFilter gatewayTenantFilter(AtengGatewayProperties properties) {
            return new GatewayTenantFilter(properties.getTenant());
        }
    }

    /**
     * 网关双轨动态路由（主配置 spring.cloud.gateway.routes 动态感知 + 独立文件 gateway-routes.json）自动装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.dynamic-route", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class DynamicRouteAutoConfiguration {

        /**
         * 注册主配置文件 (spring.cloud.gateway.routes) 环境变量动态感知与安全热更监听器
         */
        @Bean
        @ConditionalOnMissingBean
        public GatewayRouteRefreshListener gatewayRouteRefreshListener(
                ObjectProvider<GatewayProperties> gatewayPropertiesProvider,
                Environment environment,
                ApplicationEventPublisher applicationEventPublisher,
                AtengGatewayProperties properties) {
            return new GatewayRouteRefreshListener(
                    gatewayPropertiesProvider.getIfAvailable(),
                    environment,
                    applicationEventPublisher,
                    properties.getDynamicRoute());
        }

        /**
         * 注册基于 Nacos 配置中心的独立动态路由仓库实现 (Track A)
         */
        @Bean
        @ConditionalOnClass(NacosConfigManager.class)
        @ConditionalOnMissingBean(RouteDefinitionRepository.class)
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

    /**
     * 网关分布式多维限流与 429 统一转译自动装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.rate-limiter", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class RateLimiterAutoConfiguration {

        /**
         * 注册按客户端真实公网 IP 限流键解析器 (默认 Primary)
         */
        @Bean(name = "ipKeyResolver")
        @Primary
        @ConditionalOnMissingBean(name = "ipKeyResolver")
        public IpKeyResolver ipKeyResolver() {
            return new IpKeyResolver();
        }

        /**
         * 注册按 Sa-Token 用户 ID 限流键解析器
         */
        @Bean(name = "userKeyResolver")
        @ConditionalOnMissingBean(name = "userKeyResolver")
        public UserKeyResolver userKeyResolver() {
            return new UserKeyResolver();
        }

        /**
         * 注册按接口路径与客户端 IP 双维度限流键解析器
         */
        @Bean(name = "apiKeyResolver")
        @ConditionalOnMissingBean(name = "apiKeyResolver")
        public ApiKeyResolver apiKeyResolver() {
            return new ApiKeyResolver();
        }

        /**
         * 注册限流触发 HTTP 429 响应统一转译全局过滤器
         */
        @Bean
        @ConditionalOnMissingBean
        public GatewayRateLimitResponseFilter gatewayRateLimitResponseFilter(ObjectMapper objectMapper) {
            return new GatewayRateLimitResponseFilter(objectMapper);
        }
    }
}
