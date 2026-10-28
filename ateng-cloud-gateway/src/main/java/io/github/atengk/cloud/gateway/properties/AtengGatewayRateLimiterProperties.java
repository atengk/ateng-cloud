package io.github.atengk.cloud.gateway.properties;

/**
 * 网关分布式多维限流配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengGatewayRateLimiterProperties {

    /**
     * 是否启用网关分布式限流防刷
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
