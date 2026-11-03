package io.github.atengk.cloud.sample.sentinel.model.security;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 边缘安全与资源防爆订单视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SecurityOrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单主键 ID
     */
    private Long orderId;

    /**
     * 业务订单编号
     */
    private String orderNo;

    /**
     * 订单应付金额
     */
    private BigDecimal amount;

    /**
     * 经 URLCleaner 归一化清洗后的 Sentinel 资源标识
     */
    private String cleanedResource;

    public SecurityOrderVO() {
    }

    public SecurityOrderVO(Long orderId, String orderNo, BigDecimal amount, String cleanedResource) {
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.amount = amount;
        this.cleanedResource = cleanedResource;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCleanedResource() {
        return cleanedResource;
    }

    public void setCleanedResource(String cleanedResource) {
        this.cleanedResource = cleanedResource;
    }
}
