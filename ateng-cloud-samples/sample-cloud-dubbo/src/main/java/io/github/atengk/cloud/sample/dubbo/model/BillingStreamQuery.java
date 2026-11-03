package io.github.atengk.cloud.sample.dubbo.model;

import jakarta.validation.constraints.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.StringJoiner;

/**
 * 流式对账查询请求对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class BillingStreamQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 结算账户 ID
     */
    @NotNull(message = "结算账户 accountId 不能为空")
    private Long accountId;

    /**
     * 流式推送单批批次容量
     */
    private Integer batchSize = 5;

    /**
     * 最大拉取明细总条数
     */
    private Integer limit = 10;

    public BillingStreamQuery() {
    }

    public BillingStreamQuery(Long accountId, Integer batchSize, Integer limit) {
        this.accountId = accountId;
        this.batchSize = batchSize;
        this.limit = limit;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", BillingStreamQuery.class.getSimpleName() + "[", "]")
                .add("accountId=" + accountId)
                .add("batchSize=" + batchSize)
                .add("limit=" + limit)
                .toString();
    }
}
