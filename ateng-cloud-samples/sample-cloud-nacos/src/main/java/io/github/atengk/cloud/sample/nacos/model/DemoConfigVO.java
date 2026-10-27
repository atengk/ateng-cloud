package io.github.atengk.cloud.sample.nacos.model;

import java.io.Serializable;
import java.time.Duration;

/**
 * 业务配置响应视图对象（严格遵循 Zero-Entity-Leakage 传输解耦契约）
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class DemoConfigVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String title;
    private String maskedSecretKey;
    private Duration timeout;
    private int poolSize;

    public DemoConfigVO() {
    }

    public DemoConfigVO(String title, String maskedSecretKey, Duration timeout, int poolSize) {
        this.title = title;
        this.maskedSecretKey = maskedSecretKey;
        this.timeout = timeout;
        this.poolSize = poolSize;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMaskedSecretKey() {
        return maskedSecretKey;
    }

    public void setMaskedSecretKey(String maskedSecretKey) {
        this.maskedSecretKey = maskedSecretKey;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }
}
