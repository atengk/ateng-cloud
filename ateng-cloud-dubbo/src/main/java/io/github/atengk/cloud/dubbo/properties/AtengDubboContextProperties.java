package io.github.atengk.cloud.dubbo.properties;

/**
 * Dubbo 全链路四维上下文透传配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengDubboContextProperties {

    /**
     * 是否开启四维上下文透传 (MDC traceId, userId, tenantId, ignoreTenant)，默认 true
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
