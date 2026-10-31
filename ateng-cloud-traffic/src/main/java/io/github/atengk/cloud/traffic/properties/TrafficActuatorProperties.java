package io.github.atengk.cloud.traffic.properties;

/**
 * 流量治理 Actuator 诊断与沙箱端点配置
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class TrafficActuatorProperties {

    /**
     * 是否启用 /actuator/traffic 运维诊断与模拟端点 (默认开启)
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
