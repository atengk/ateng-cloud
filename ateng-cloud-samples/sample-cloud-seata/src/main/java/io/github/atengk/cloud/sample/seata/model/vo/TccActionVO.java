package io.github.atengk.cloud.sample.seata.model.vo;

import java.io.Serial;
import java.io.Serializable;

/**
 * TCC 防卫动作响应视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class TccActionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 全局事务 XID
     */
    private String xid;

    /**
     * 分支事务 ID
     */
    private Long branchId;

    /**
     * 账户用户 ID
     */
    private String userId;

    /**
     * 当前 TCC 防卫状态（TRIED / COMMITTED / ROLLBACKED / SUSPENDED）
     */
    private String status;

    /**
     * 账户当前剩余余额（元）
     */
    private Integer balanceRemaining;

    /**
     * 防卫动作与流转结果描述
     */
    private String message;

    public TccActionVO() {
    }

    public TccActionVO(String xid, Long branchId, String userId, String status, Integer balanceRemaining, String message) {
        this.xid = xid;
        this.branchId = branchId;
        this.userId = userId;
        this.status = status;
        this.balanceRemaining = balanceRemaining;
        this.message = message;
    }

    public String getXid() {
        return xid;
    }

    public void setXid(String xid) {
        this.xid = xid;
    }

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getBalanceRemaining() {
        return balanceRemaining;
    }

    public void setBalanceRemaining(Integer balanceRemaining) {
        this.balanceRemaining = balanceRemaining;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "TccActionVO{" +
                "xid='" + xid + '\'' +
                ", branchId=" + branchId +
                ", userId='" + userId + '\'' +
                ", status='" + status + '\'' +
                ", balanceRemaining=" + balanceRemaining +
                ", message='" + message + '\'' +
                '}';
    }
}
