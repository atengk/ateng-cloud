package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 开放平台商户订单同步传输请求
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class OpenApiOrderSyncDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 外部商户订单流水号
     */
    private String orderId;

    /**
     * 商户编码
     */
    private String merchantId;

    /**
     * 交易金额
     */
    private BigDecimal amount;

    /**
     * 支付渠道或同步渠道
     */
    private String channel;

    public OpenApiOrderSyncDTO() {
    }

    public OpenApiOrderSyncDTO(String orderId, String merchantId, BigDecimal amount, String channel) {
        this.orderId = orderId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.channel = channel;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }
}
