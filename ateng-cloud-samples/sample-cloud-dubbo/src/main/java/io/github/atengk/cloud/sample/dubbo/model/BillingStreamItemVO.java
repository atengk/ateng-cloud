package io.github.atengk.cloud.sample.dubbo.model;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.StringJoiner;

/**
 * 流式对账明细条目视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class BillingStreamItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 结算流水号
     */
    private String billingNo;

    /**
     * 订单编号
     */
    private String orderNo;

    /**
     * 交易金额
     */
    private BigDecimal amount;

    /**
     * 结算状态
     */
    private String status;

    /**
     * 当前批次索引编号
     */
    private Integer batchIndex;

    /**
     * 时间戳
     */
    private LocalDateTime timestamp;

    public BillingStreamItemVO() {
    }

    public BillingStreamItemVO(String billingNo, String orderNo, BigDecimal amount, String status, Integer batchIndex, LocalDateTime timestamp) {
        this.billingNo = billingNo;
        this.orderNo = orderNo;
        this.amount = amount;
        this.status = status;
        this.batchIndex = batchIndex;
        this.timestamp = timestamp;
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

    public Integer getBatchIndex() {
        return batchIndex;
    }

    public void setBatchIndex(Integer batchIndex) {
        this.batchIndex = batchIndex;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", BillingStreamItemVO.class.getSimpleName() + "[", "]")
                .add("billingNo='" + billingNo + "'")
                .add("orderNo='" + orderNo + "'")
                .add("amount=" + amount)
                .add("status='" + status + "'")
                .add("batchIndex=" + batchIndex)
                .add("timestamp=" + timestamp)
                .toString();
    }
}
