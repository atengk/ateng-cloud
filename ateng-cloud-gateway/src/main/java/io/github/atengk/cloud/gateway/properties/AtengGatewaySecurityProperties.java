package io.github.atengk.cloud.gateway.properties;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            GatewayConstants.SAME_TOKEN_UPPER,
            GatewayConstants.SA_SAME_TOKEN,
            GatewayConstants.TX_XID,
            GatewayConstants.X_TRAFFIC_LANE,
            GatewayConstants.X_TRAFFIC_RULE
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
     * 是否启用响应式无锁链路追踪 Trace 过滤器
     */
    private boolean traceEnabled = true;

    /**
     * 是否启用 Sa-Token 响应式集中鉴权与同源签名过滤器
     */
    private boolean saTokenEnabled = true;

    /**
     * 是否向下游注入 Sa-Token 内部同源 Same-Token 受信凭据 (仅当 saTokenEnabled=true 时生效)
     */
    private boolean sameTokenEnabled = true;

    /**
     * Sa-Token 鉴权忽略放行的 AntPath 路径列表 (如 /auth/**, /actuator/**)
     */
    private List<String> excludePaths = new ArrayList<>();

    /**
     * 默认 Sa-Token 账号体系标识 (未命中 loginTypeMappings 路由映射时使用，默认 login)
     */
    private String defaultLoginType = "login";

    /**
     * 多账号体系路由映射规则 (Key 为账号体系 loginType，如 admin、member；Value 为匹配的 AntPath 路径模式列表，如 /admin/**)
     * <p>命中多条规则时按最具体模式优先仲裁，同特异度保留配置声明顺序</p>
     */
    private Map<String, List<String>> loginTypeMappings = new LinkedHashMap<>();

    /**
     * 是否启用复合 loginId ({tenantId}_{userId}) 自动解包兼容
     * <p>当 Session 中缺少 login_user 载荷时，受控按首个下划线拆解还原租户与用户 ID，默认 true</p>
     */
    private boolean compositeLoginIdEnabled = true;

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

    public boolean isTraceEnabled() {
        return traceEnabled;
    }

    public void setTraceEnabled(boolean traceEnabled) {
        this.traceEnabled = traceEnabled;
    }

    public boolean isSaTokenEnabled() {
        return saTokenEnabled;
    }

    public void setSaTokenEnabled(boolean saTokenEnabled) {
        this.saTokenEnabled = saTokenEnabled;
    }

    public boolean isSameTokenEnabled() {
        return sameTokenEnabled;
    }

    public void setSameTokenEnabled(boolean sameTokenEnabled) {
        this.sameTokenEnabled = sameTokenEnabled;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }

    public String getDefaultLoginType() {
        return defaultLoginType;
    }

    public void setDefaultLoginType(String defaultLoginType) {
        this.defaultLoginType = defaultLoginType;
    }

    public Map<String, List<String>> getLoginTypeMappings() {
        return loginTypeMappings;
    }

    public void setLoginTypeMappings(Map<String, List<String>> loginTypeMappings) {
        this.loginTypeMappings = loginTypeMappings;
    }

    public boolean isCompositeLoginIdEnabled() {
        return compositeLoginIdEnabled;
    }

    public void setCompositeLoginIdEnabled(boolean compositeLoginIdEnabled) {
        this.compositeLoginIdEnabled = compositeLoginIdEnabled;
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
}
