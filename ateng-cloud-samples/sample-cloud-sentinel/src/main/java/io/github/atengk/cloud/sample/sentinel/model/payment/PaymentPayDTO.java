package io.github.atengk.cloud.sample.sentinel.model.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 支付请求传输对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class PaymentPayDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务订单编号
     */
    @NotBlank(message = "业务订单编号不能为空")
    private String orderNo;

    /**
     * 支付金额（必须大于 0）
     */
    @NotNull(message = "支付金额不能为空")
    @DecimalMin(value = "0.01", message = "支付金额必须大于 0")
    private BigDecimal amount;

    /**
     * 支付渠道（如 ALIPAY、WECHAT 等）
     */
    @NotBlank(message = "支付渠道不能为空")
    private String channel;

    public PaymentPayDTO() {
    }

    public PaymentPayDTO(String orderNo, BigDecimal amount, String channel) {
        this.orderNo = orderNo;
        this.amount = amount;
        this.channel = channel;
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

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }
}
