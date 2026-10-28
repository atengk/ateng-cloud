package io.github.atengk.cloud.gateway.properties;

/**
 * 网关流量染色与金丝雀灰度配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengGatewayTrafficProperties {

    /**
     * 是否启用流量染色与灰度路由解析
     */
    private boolean enabled = true;

    /**
     * 当灰度实例全部下线时，是否自动平滑降级调度至基线默认实例 (默认 true，杜绝全网 503)
     */
    private boolean fallbackToDefault = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFallbackToDefault() {
        return fallbackToDefault;
    }

    public void setFallbackToDefault(boolean fallbackToDefault) {
        this.fallbackToDefault = fallbackToDefault;
    }
}
