package io.github.atengk.cloud.sample.seata.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serial;
import java.io.Serializable;

/**
 * 采购提单请求传输对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class PurchaseOrderDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 采购用户 ID
     */
    @NotBlank(message = "用户 ID 不能为空")
    private String userId;

    /**
     * 商品编码
     */
    @NotBlank(message = "商品编码不能为空")
    private String commodityCode;

    /**
     * 订购数量
     */
    @NotNull(message = "订购数量不能为空")
    @Min(value = 1, message = "订购数量必须大于等于 1")
    private Integer count;

    /**
     * 是否模拟异常触发分布式事务两阶段回滚
     */
    private Boolean forceRollback;

    public PurchaseOrderDTO() {
    }

    public PurchaseOrderDTO(String userId, String commodityCode, Integer count) {
        this.userId = userId;
        this.commodityCode = commodityCode;
        this.count = count;
    }

    public PurchaseOrderDTO(String userId, String commodityCode, Integer count, Boolean forceRollback) {
        this.userId = userId;
        this.commodityCode = commodityCode;
        this.count = count;
        this.forceRollback = forceRollback;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCommodityCode() {
        return commodityCode;
    }

    public void setCommodityCode(String commodityCode) {
        this.commodityCode = commodityCode;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public Boolean getForceRollback() {
        return forceRollback;
    }

    public void setForceRollback(Boolean forceRollback) {
        this.forceRollback = forceRollback;
    }

    @Override
    public String toString() {
        return "PurchaseOrderDTO{" +
                "userId='" + userId + '\'' +
                ", commodityCode='" + commodityCode + '\'' +
                ", count=" + count +
                ", forceRollback=" + forceRollback +
                '}';
    }
}
