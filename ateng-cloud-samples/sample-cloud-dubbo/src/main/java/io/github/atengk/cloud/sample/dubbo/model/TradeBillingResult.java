package io.github.atengk.cloud.sample.dubbo.model;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.StringJoiner;

/**
 * 交易撮合与结算结果视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class TradeBillingResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 结算单流水号
     */
    private String billingNo;

    /**
     * 关联业务订单号
     */
    private String orderNo;

    /**
     * 结算账户 ID
     */
    private Long accountId;

    /**
     * 实际结算扣款金额
     */
    private BigDecimal amount;

    /**
     * 账户剩余可用余额
     */
    private BigDecimal balance;

    /**
     * 结算状态 (SUCCESS / FAILED)
     */
    private String status;

    /**
     * 执行处理节点主机标识
     */
    private String processNode;

    /**
     * 全链路分布式调用链 Trace ID
     */
    private String traceId;

    /**
     * 结算完成时间
     */
    private LocalDateTime settleTime;

    public TradeBillingResult() {
    }

    public TradeBillingResult(String billingNo, String orderNo, Long accountId, BigDecimal amount, BigDecimal balance, String status, String processNode, String traceId, LocalDateTime settleTime) {
        this.billingNo = billingNo;
        this.orderNo = orderNo;
        this.accountId = accountId;
        this.amount = amount;
        this.balance = balance;
        this.status = status;
        this.processNode = processNode;
        this.traceId = traceId;
        this.settleTime = settleTime;
    }

    public String getBillingNo() {
        return billingNo;
    }

    public void setBillingNo(String billingNo) {
        this.billingNo = billingNo;
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

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getProcessNode() {
        return processNode;
    }

    public void setProcessNode(String processNode) {
        this.processNode = processNode;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public LocalDateTime getSettleTime() {
        return settleTime;
    }

    public void setSettleTime(LocalDateTime settleTime) {
        this.settleTime = settleTime;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", TradeBillingResult.class.getSimpleName() + "[", "]")
                .add("billingNo='" + billingNo + "'")
                .add("orderNo='" + orderNo + "'")
                .add("accountId=" + accountId)
                .add("amount=" + amount)
                .add("balance=" + balance)
                .add("status='" + status + "'")
                .add("processNode='" + processNode + "'")
                .add("traceId='" + traceId + "'")
                .add("settleTime=" + settleTime)
                .toString();
    }
}
