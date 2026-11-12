package io.github.atengk.cloud.dubbo.properties;

import java.time.Duration;

/**
 * Dubbo RPC 慢调用监测与告警配置属性
 *
 * @author Ateng
 * @since 2026-11-12
 */
public class AtengDubboSlowCallProperties {

    /**
     * 是否开启 RPC 慢调用主动监测与指标统计，默认 true
     */
    private boolean enabled = true;

    /**
     * 慢调用判定耗时阈值，单次调用耗时达到或超过该阈值时输出 WARN 日志并累加指标；默认 3000ms，设为 0 或负数时关闭
     */
    private Duration threshold = Duration.ofMillis(3000);

    /**
     * 是否在 Consumer 端开启慢调用监测，默认 true
     */
    private boolean includeConsumer = true;

    /**
     * 是否在 Provider 端开启慢调用监测，默认 true
     */
    private boolean includeProvider = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getThreshold() {
        return threshold;
    }

    public void setThreshold(Duration threshold) {
        this.threshold = threshold;
    }

    public boolean isIncludeConsumer() {
        return includeConsumer;
    }

    public void setIncludeConsumer(boolean includeConsumer) {
        this.includeConsumer = includeConsumer;
    }

    public boolean isIncludeProvider() {
        return includeProvider;
    }

    public void setIncludeProvider(boolean includeProvider) {
        this.includeProvider = includeProvider;
    }
}
