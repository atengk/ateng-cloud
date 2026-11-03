package io.github.atengk.cloud.sample.sentinel.model.payment;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付结果视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class PaymentResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付平台交易流水号
     */
    private String payNo;

    /**
     * 业务订单号
     */
    private String orderNo;

    /**
     * 实付金额
     */
    private BigDecimal amount;

    /**
     * 支付状态 (SUCCESS / DEGRADED / FAILED)
     */
    private String status;

    /**
     * 状态提示说明
     */
    private String message;

    /**
     * 是否命中降级保底分支
     */
    private Boolean fallback;

    /**
     * 交易完成时间
     */
    private LocalDateTime payTime;

    public PaymentResultVO() {
    }

    public PaymentResultVO(String payNo, String orderNo, BigDecimal amount, String status, String message, Boolean fallback, LocalDateTime payTime) {
        this.payNo = payNo;
        this.orderNo = orderNo;
        this.amount = amount;
        this.status = status;
        this.message = message;
        this.fallback = fallback;
        this.payTime = payTime;
    }

    /**
     * 快速构建柔性降级保底结果对象
     *
     * @param payNo 支付流水号
     * @param message 降级提示信息
     * @return 降级保底视图对象
     */
    public static PaymentResultVO fallback(String payNo, String message) {
        return new PaymentResultVO(payNo, "UNKNOWN", BigDecimal.ZERO, "DEGRADED", message, true, LocalDateTime.now());
    }

    public String getPayNo() {
        return payNo;
    }

    public void setPayNo(String payNo) {
        this.payNo = payNo;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Boolean getFallback() {
        return fallback;
    }

    public void setFallback(Boolean fallback) {
        this.fallback = fallback;
    }

    public LocalDateTime getPayTime() {
        return payTime;
    }

    public void setPayTime(LocalDateTime payTime) {
        this.payTime = payTime;
    }
}
