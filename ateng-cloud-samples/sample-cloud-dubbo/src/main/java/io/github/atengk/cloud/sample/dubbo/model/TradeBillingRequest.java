package io.github.atengk.cloud.sample.dubbo.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.StringJoiner;

/**
 * 交易撮合与结算请求参数对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class TradeBillingRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务订单编号
     */
    @NotBlank(message = "订单编号 orderNo 不能为空")
    private String orderNo;

    /**
     * 结算账户 ID
     */
    @NotNull(message = "结算账户 accountId 不能为空")
    private Long accountId;

    /**
     * 交易结算金额
     */
    @NotNull(message = "交易结算金额 amount 不能为空")
    @DecimalMin(value = "0.01", message = "结算金额 amount 必须大于 0.00")
    private BigDecimal amount;

    /**
     * 交易结算类型 (如 PURCHASE, REFUND, SETTLE)
     */
    @NotBlank(message = "交易类型 tradeType 不能为空")
    private String tradeType;

    /**
     * 多租户标识
     */
    private String tenantId;

    /**
     * 流量染色标签 (如 canary-v1)
     */
    private String trafficTag;

    public TradeBillingRequest() {
    }

    public TradeBillingRequest(String orderNo, Long accountId, BigDecimal amount, String tradeType) {
        this.orderNo = orderNo;
        this.accountId = accountId;
        this.amount = amount;
        this.tradeType = tradeType;
    }

    public TradeBillingRequest(String orderNo, Long accountId, BigDecimal amount, String tradeType, String tenantId, String trafficTag) {
        this.orderNo = orderNo;
        this.accountId = accountId;
        this.amount = amount;
        this.tradeType = tradeType;
        this.tenantId = tenantId;
        this.trafficTag = trafficTag;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getTradeType() {
        return tradeType;
    }

    public void setTradeType(String tradeType) {
        this.tradeType = tradeType;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTrafficTag() {
        return trafficTag;
    }

    public void setTrafficTag(String trafficTag) {
        this.trafficTag = trafficTag;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", TradeBillingRequest.class.getSimpleName() + "[", "]")
                .add("orderNo='" + orderNo + "'")
                .add("accountId=" + accountId)
                .add("amount=" + amount)
                .add("tradeType='" + tradeType + "'")
                .add("tenantId='" + tenantId + "'")
                .add("trafficTag='" + trafficTag + "'")
                .toString();
    }
}
