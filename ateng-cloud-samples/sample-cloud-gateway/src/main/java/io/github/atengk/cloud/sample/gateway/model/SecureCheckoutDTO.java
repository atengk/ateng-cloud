package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;

/**
 * 网关边缘混合加解密结算请求 DTO 模型
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class SecureCheckoutDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    private String orderNo;

    /**
     * 结算总金额（单位：分）
     */
    private Long amountCents;

    /**
     * 收款账户掩码备注
     */
    private String remark;

    public SecureCheckoutDTO() {
    }

    public SecureCheckoutDTO(String orderNo, Long amountCents, String remark) {
        this.orderNo = orderNo;
        this.amountCents = amountCents;
        this.remark = remark;
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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
