package io.github.atengk.cloud.gateway.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * ateng-cloud 网关顶层核心配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
@ConfigurationProperties(prefix = "ateng.cloud.gateway")
public class AtengGatewayProperties {

    /**
     * 是否启用网关 Starter 增强底座
     */
    private boolean enabled = true;

    /**
     * Netty 堆外直接内存请求体硬限制 (默认 2MB，超大请求体直接 413 熔断防 OOM，大文件走 S3 预签名直传)
     */
    private DataSize maxInMemorySize = DataSize.ofMegabytes(2);

    /**
     * 下游微服务默认连接超时阈值 (快速失败，默认 3s)
     */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * 下游微服务默认读取响应超时阈值 (默认 10s)
     */
    private Duration responseTimeout = Duration.ofSeconds(10);

    /**
     * 安全防御与 Sa-Token 鉴权配置
     */
    @NestedConfigurationProperty
    private AtengGatewaySecurityProperties security = new AtengGatewaySecurityProperties();

    /**
     * OWASP 与等保三级安全响应头配置
     */
    @NestedConfigurationProperty
    private AtengGatewaySecurityHeadersProperties securityHeaders = new AtengGatewaySecurityHeadersProperties();

    /**
     * Nacos 动态路由配置
     */
    @NestedConfigurationProperty
    private AtengGatewayDynamicRouteProperties dynamicRoute = new AtengGatewayDynamicRouteProperties();

    /**
     * 全链路流量染色与灰度路由配置
     */
    @NestedConfigurationProperty
    private AtengGatewayTrafficProperties traffic = new AtengGatewayTrafficProperties();

    /**
     * 轻量响应式访问日志审计配置
     */
    @NestedConfigurationProperty
    private AtengGatewayAccessLogProperties accessLog = new AtengGatewayAccessLogProperties();

    /**
     * SpringDoc 微服务接口文档动态聚合配置
     */
    @NestedConfigurationProperty
    private AtengGatewayOpenApiProperties openapi = new AtengGatewayOpenApiProperties();

    /**
     * Redis 分布式多维限流配置
     */
    @NestedConfigurationProperty
    private AtengGatewayRateLimiterProperties rateLimiter = new AtengGatewayRateLimiterProperties();

    /**
     * 开放接口数字签名与防重放配置
     */
    @NestedConfigurationProperty
    private AtengGatewaySignProperties sign = new AtengGatewaySignProperties();

    /**
     * 多租户入站解析配置
     */
    @NestedConfigurationProperty
    private AtengGatewayTenantProperties tenant = new AtengGatewayTenantProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DataSize getMaxInMemorySize() {
        return maxInMemorySize;
    }

    public void setMaxInMemorySize(DataSize maxInMemorySize) {
        this.maxInMemorySize = maxInMemorySize;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public AtengGatewaySecurityProperties getSecurity() {
        return security;
    }

    public void setSecurity(AtengGatewaySecurityProperties security) {
        this.security = security;
    }

    public AtengGatewaySecurityHeadersProperties getSecurityHeaders() {
        return securityHeaders;
    }

    public void setSecurityHeaders(AtengGatewaySecurityHeadersProperties securityHeaders) {
        this.securityHeaders = securityHeaders;
    }

    public AtengGatewayDynamicRouteProperties getDynamicRoute() {
        return dynamicRoute;
    }

    public void setDynamicRoute(AtengGatewayDynamicRouteProperties dynamicRoute) {
        this.dynamicRoute = dynamicRoute;
    }

    public AtengGatewayTrafficProperties getTraffic() {
        return traffic;
    }

    public void setTraffic(AtengGatewayTrafficProperties traffic) {
        this.traffic = traffic;
    }

    public AtengGatewayAccessLogProperties getAccessLog() {
        return accessLog;
    }

    public void setAccessLog(AtengGatewayAccessLogProperties accessLog) {
        this.accessLog = accessLog;
    }

    public AtengGatewayOpenApiProperties getOpenapi() {
        return openapi;
    }

    public void setOpenapi(AtengGatewayOpenApiProperties openapi) {
        this.openapi = openapi;
    }

    public AtengGatewayRateLimiterProperties getRateLimiter() {
        return rateLimiter;
    }

    public void setRateLimiter(AtengGatewayRateLimiterProperties rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public AtengGatewaySignProperties getSign() {
        return sign;
    }

    public void setSign(AtengGatewaySignProperties sign) {
        this.sign = sign;
    }

    public AtengGatewayTenantProperties getTenant() {
        return tenant;
    }

    public void setTenant(AtengGatewayTenantProperties tenant) {
        this.tenant = tenant;
    }
}
