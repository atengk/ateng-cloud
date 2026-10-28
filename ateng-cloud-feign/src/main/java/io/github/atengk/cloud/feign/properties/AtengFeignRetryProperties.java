package io.github.atengk.cloud.feign.properties;

import java.time.Duration;

/**
 * Feign 客户端防雪崩弹性重试配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignRetryProperties {

    /**
     * 是否开启弹性重试纪律，默认 true
     */
    private boolean enabled = true;

    /**
     * 重试初始退避休眠间隔，默认 100ms
     */
    private Duration period = Duration.ofMillis(100);

    /**
     * 重试最大退避休眠间隔，默认 1s
     */
    private Duration maxPeriod = Duration.ofSeconds(1);

    /**
     * 最大请求次数（包含首次请求在内），默认 2 次（即初始 1 次 + 最多弹性重试 1 次）
     */
    private int maxAttempts = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPeriod() {
        return period;
    }

    public void setPeriod(Duration period) {
        this.period = period;
    }

    public Duration getMaxPeriod() {
        return maxPeriod;
    }

    public void setMaxPeriod(Duration maxPeriod) {
        this.maxPeriod = maxPeriod;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
