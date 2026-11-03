package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;

/**
 * 开放平台商户订单同步响应视图
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class OpenApiOrderSyncVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 网关分配的同步批次流水号
     */
    private String syncId;

    /**
     * 外部商户订单流水号
     */
    private String orderId;

    /**
     * 同步处理状态
     */
    private String status;

    /**
     * 处理响应时间戳
     */
    private long timestamp;

    /**
     * API 签名与防重放核验标记
     */
    private boolean signatureVerified;

    public OpenApiOrderSyncVO() {
    }

    public OpenApiOrderSyncVO(String syncId, String orderId, String status, long timestamp, boolean signatureVerified) {
        this.syncId = syncId;
        this.orderId = orderId;
        this.status = status;
        this.timestamp = timestamp;
        this.signatureVerified = signatureVerified;
    }

    public String getSyncId() {
        return syncId;
    }

    public void setSyncId(String syncId) {
        this.syncId = syncId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isSignatureVerified() {
        return signatureVerified;
    }

    public void setSignatureVerified(boolean signatureVerified) {
        this.signatureVerified = signatureVerified;
    }
}
