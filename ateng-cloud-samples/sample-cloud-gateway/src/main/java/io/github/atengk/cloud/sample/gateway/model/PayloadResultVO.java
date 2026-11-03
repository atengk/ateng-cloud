package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;

/**
 * 载荷防爆防御检测响应视图
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class PayloadResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实际接收到的请求体字节数
     */
    private int receivedBytes;

    /**
     * 网关允许的最大载荷字节数
     */
    private long maxAllowedBytes;

    /**
     * 载荷校验是否通过
     */
    private boolean passed;

    public PayloadResultVO() {
    }

    public PayloadResultVO(int receivedBytes, long maxAllowedBytes, boolean passed) {
        this.receivedBytes = receivedBytes;
        this.maxAllowedBytes = maxAllowedBytes;
        this.passed = passed;
    }

    public int getReceivedBytes() {
        return receivedBytes;
    }

    public void setReceivedBytes(int receivedBytes) {
        this.receivedBytes = receivedBytes;
    }

    public long getMaxAllowedBytes() {
        return maxAllowedBytes;
    }

    public void setMaxAllowedBytes(long maxAllowedBytes) {
        this.maxAllowedBytes = maxAllowedBytes;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }
}
