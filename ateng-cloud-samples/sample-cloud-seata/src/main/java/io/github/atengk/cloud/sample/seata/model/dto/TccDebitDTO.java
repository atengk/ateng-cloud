package io.github.atengk.cloud.sample.seata.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serial;
import java.io.Serializable;

/**
 * TCC 防卫扣款请求传输对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class TccDebitDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 全局事务 XID（若为空则系统自动生成离线自洽 XID）
     */
    private String xid;

    /**
     * 分支事务 ID（若为空则系统自动分配）
     */
    private Long branchId;

    /**
     * 账户用户 ID
     */
    @NotBlank(message = "用户 ID 不能为空")
    private String userId;

    /**
     * 扣减金额（元）
     */
    @NotNull(message = "扣减金额不能为空")
    @Min(value = 1, message = "扣减金额必须大于等于 1")
    private Integer money;

    public TccDebitDTO() {
    }

    public TccDebitDTO(String userId, Integer money) {
        this.userId = userId;
        this.money = money;
    }

    public TccDebitDTO(String xid, Long branchId, String userId, Integer money) {
        this.xid = xid;
        this.branchId = branchId;
        this.userId = userId;
        this.money = money;
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

    public Integer getMoney() {
        return money;
    }

    public void setMoney(Integer money) {
        this.money = money;
    }

    @Override
    public String toString() {
        return "TccDebitDTO{" +
                "xid='" + xid + '\'' +
                ", branchId=" + branchId +
                ", userId='" + userId + '\'' +
                ", money=" + money +
                '}';
    }
}
