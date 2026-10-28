package io.github.atengk.cloud.gateway.properties;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 网关安全防御与鉴权配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengGatewaySecurityProperties {

    /**
     * 是否启用安全守卫与鉴权过滤器
     */
    private boolean enabled = true;

    /**
     * 是否启用 URI 规范化与目录穿越防卫过滤器
     */
    private boolean pathValidationEnabled = true;

    /**
     * 是否启用入站敏感标头物理剥离清洗过滤器
     */
    private boolean headerSanitizeEnabled = true;

    /**
     * 入站需强制剥离清洗的内部敏感标头列表
     */
    private List<String> sanitizeHeaders = new ArrayList<>(Arrays.asList(
            GatewayConstants.X_USER_ID,
            GatewayConstants.X_TENANT_ID,
            GatewayConstants.X_IGNORE_TENANT,
            GatewayConstants.SAME_TOKEN,
            GatewayConstants.SAME_TOKEN_UPPER
    ));

    /**
     * 是否启用真实公网客户端 IP 解析过滤器
     */
    private boolean clientIpEnabled = true;

    /**
     * 是否启用 IP 黑白名单过滤守卫
     */
    private boolean ipFilterEnabled = true;

    /**
     * Sa-Token 鉴权忽略放行的 AntPath 路径列表 (如 /auth/**, /actuator/**)
     */
    private List<String> excludePaths = new ArrayList<>();

    /**
     * 前置可信反向代理跳数 (用于从右往左安全解析真实公网客户端 IP，默认 1)
     */
    private int trustedProxyCount = 1;

    /**
     * IP 黑名单列表 (支持单 IP 如 192.168.1.100 或 CIDR 如 10.0.0.0/8，命中直接 403 阻断)
     */
    private List<String> ipBlacklist = new ArrayList<>();

    /**
     * IP 白名单列表 (若配置非空，则仅放行白名单 IP，其它直接 403 阻断)
     */
    private List<String> ipWhitelist = new ArrayList<>();

    /**
     * 出站安全响应头强化配置
     */
    @NestedConfigurationProperty
    private AtengGatewaySecurityHeadersProperties securityHeaders = new AtengGatewaySecurityHeadersProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPathValidationEnabled() {
        return pathValidationEnabled;
    }

    public void setPathValidationEnabled(boolean pathValidationEnabled) {
        this.pathValidationEnabled = pathValidationEnabled;
    }

    public boolean isHeaderSanitizeEnabled() {
        return headerSanitizeEnabled;
    }

    public void setHeaderSanitizeEnabled(boolean headerSanitizeEnabled) {
        this.headerSanitizeEnabled = headerSanitizeEnabled;
    }

    public List<String> getSanitizeHeaders() {
        return sanitizeHeaders;
    }

    public void setSanitizeHeaders(List<String> sanitizeHeaders) {
        this.sanitizeHeaders = sanitizeHeaders;
    }

    public boolean isClientIpEnabled() {
        return clientIpEnabled;
    }

    public void setClientIpEnabled(boolean clientIpEnabled) {
        this.clientIpEnabled = clientIpEnabled;
    }

    public boolean isIpFilterEnabled() {
        return ipFilterEnabled;
    }

    public void setIpFilterEnabled(boolean ipFilterEnabled) {
        this.ipFilterEnabled = ipFilterEnabled;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }

    public int getTrustedProxyCount() {
        return trustedProxyCount;
    }

    public void setTrustedProxyCount(int trustedProxyCount) {
        this.trustedProxyCount = trustedProxyCount;
    }

    public List<String> getIpBlacklist() {
        return ipBlacklist;
    }

    public void setIpBlacklist(List<String> ipBlacklist) {
        this.ipBlacklist = ipBlacklist;
    }

    public List<String> getIpWhitelist() {
        return ipWhitelist;
    }

    public void setIpWhitelist(List<String> ipWhitelist) {
        this.ipWhitelist = ipWhitelist;
    }

    public AtengGatewaySecurityHeadersProperties getSecurityHeaders() {
        return securityHeaders;
    }

    public void setSecurityHeaders(AtengGatewaySecurityHeadersProperties securityHeaders) {
        this.securityHeaders = securityHeaders;
    }
}
