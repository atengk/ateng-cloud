package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;

/**
 * 网关边缘混合加解密结算响应 VO 视图对象
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class SecureCheckoutVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    private String orderNo;

    /**
     * 结算金额（单位：分）
     */
    private Long amountCents;

    /**
     * 网关剥离后透传的应用标识（验证 X-App-Id 保留）
     */
    private String receivedAppId;

    /**
     * 下游是否仍可见原始加密头（验证 stripCryptoHeaders 剥离效果）
     */
    private boolean encryptHeaderVisibleToDownstream;

    /**
     * 结算状态
     */
    private String status;

    public SecureCheckoutVO() {
    }

    public SecureCheckoutVO(String orderNo,
                            Long amountCents,
                            String receivedAppId,
                            boolean encryptHeaderVisibleToDownstream,
                            String status) {
        this.orderNo = orderNo;
        this.amountCents = amountCents;
        this.receivedAppId = receivedAppId;
        this.encryptHeaderVisibleToDownstream = encryptHeaderVisibleToDownstream;
        this.status = status;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(Long amountCents) {
        this.amountCents = amountCents;
    }

    public String getReceivedAppId() {
        return receivedAppId;
    }

    public void setReceivedAppId(String receivedAppId) {
        this.receivedAppId = receivedAppId;
    }

    public boolean isEncryptHeaderVisibleToDownstream() {
        return encryptHeaderVisibleToDownstream;
    }

    public void setEncryptHeaderVisibleToDownstream(boolean encryptHeaderVisibleToDownstream) {
        this.encryptHeaderVisibleToDownstream = encryptHeaderVisibleToDownstream;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
