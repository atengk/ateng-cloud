package io.github.atengk.cloud.gateway.properties;

import java.time.Duration;

/**
 * 开放接口数字签名与防重放配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengGatewaySignProperties {

    /**
     * 是否启用开放接口签名防篡改与 Nonce 防重放
     */
    private boolean enabled = false;

    /**
     * 时间戳允许的最大时钟偏差 (默认 5 分钟)
     */
    private Duration timestampThreshold = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTimestampThreshold() {
        return timestampThreshold;
    }

    public void setTimestampThreshold(Duration timestampThreshold) {
        this.timestampThreshold = timestampThreshold;
    }
}
