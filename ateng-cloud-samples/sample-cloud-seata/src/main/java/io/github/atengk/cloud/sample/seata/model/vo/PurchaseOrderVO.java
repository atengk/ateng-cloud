package io.github.atengk.cloud.sample.seata.model.vo;

import java.io.Serial;
import java.io.Serializable;

/**
 * 采购提单视图响应对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class PurchaseOrderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 生成的订单主键 ID
     */
    private Long orderId;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 商品编码
     */
    private String commodityCode;

    /**
     * 订购数量
     */
    private Integer count;

    /**
     * 订单总金额（元）
     */
    private Integer orderMoney;

    /**
     * 扣减后剩余库存
     */
    private Integer stockRemaining;

    /**
     * 扣减后剩余账户余额
     */
    private Integer balanceRemaining;

    /**
     * 全局事务唯一标识（XID）
     */
    private String xid;

    /**
     * 事务执行状态描述
     */
    private String status;

    public PurchaseOrderVO() {
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
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

    public Integer getOrderMoney() {
        return orderMoney;
    }

    public void setOrderMoney(Integer orderMoney) {
        this.orderMoney = orderMoney;
    }

    public Integer getStockRemaining() {
        return stockRemaining;
    }

    public void setStockRemaining(Integer stockRemaining) {
        this.stockRemaining = stockRemaining;
    }

    public Integer getBalanceRemaining() {
        return balanceRemaining;
    }

    public void setBalanceRemaining(Integer balanceRemaining) {
        this.balanceRemaining = balanceRemaining;
    }

    public String getXid() {
        return xid;
    }

    public void setXid(String xid) {
        this.xid = xid;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "PurchaseOrderVO{" +
                "orderId=" + orderId +
                ", userId='" + userId + '\'' +
                ", commodityCode='" + commodityCode + '\'' +
                ", count=" + count +
                ", orderMoney=" + orderMoney +
                ", stockRemaining=" + stockRemaining +
                ", balanceRemaining=" + balanceRemaining +
                ", xid='" + xid + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
