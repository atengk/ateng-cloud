package io.github.atengk.cloud.gateway.properties;

/**
 * 网关出站安全响应头配置 (OWASP 与等保合规)
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengGatewaySecurityHeadersProperties {

    /**
     * 是否启用安全响应头自动注入
     */
    private boolean enabled = true;

    /**
     * 防 MIME 类型嗅探混淆标头
     */
    private String contentTypeOptions = "nosniff";

    /**
     * 点击劫持防范 iframe 嵌入标头 (SAMEORIGIN | DENY)
     */
    private String frameOptions = "SAMEORIGIN";

    /**
     * 浏览器 XSS 过滤器阻断标头
     */
    private String xssProtection = "1; mode=block";

    /**
     * 严格传输安全 HSTS 标头 (HTTPS 强制)
     */
    private String hsts = "max-age=31536000; includeSubDomains";

    /**
     * 跨域跳转 Referrer 来源安全策略
     */
    private String referrerPolicy = "strict-origin-when-cross-origin";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getContentTypeOptions() {
        return contentTypeOptions;
    }

    public void setContentTypeOptions(String contentTypeOptions) {
        this.contentTypeOptions = contentTypeOptions;
    }

    public String getFrameOptions() {
        return frameOptions;
    }

    public void setFrameOptions(String frameOptions) {
        this.frameOptions = frameOptions;
    }

    public String getXssProtection() {
        return xssProtection;
    }

    public void setXssProtection(String xssProtection) {
        this.xssProtection = xssProtection;
    }

    public String getHsts() {
        return hsts;
    }

    public void setHsts(String hsts) {
        this.hsts = hsts;
    }

    public String getReferrerPolicy() {
        return referrerPolicy;
    }

    public void setReferrerPolicy(String referrerPolicy) {
        this.referrerPolicy = referrerPolicy;
    }
}
