package io.github.atengk.cloud.sample.seata.model.vo;

import java.io.Serial;
import java.io.Serializable;

/**
 * 账户余额与商品库存对比视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class AccountStockVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 账户当前余额（元）
     */
    private Integer balance;

    /**
     * 商品编码
     */
    private String commodityCode;

    /**
     * 当前商品剩余库存（件）
     */
    private Integer stock;

    public AccountStockVO() {
    }

    public AccountStockVO(String userId, Integer balance, String commodityCode, Integer stock) {
        this.userId = userId;
        this.balance = balance;
        this.commodityCode = commodityCode;
        this.stock = stock;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Integer getBalance() {
        return balance;
    }

    public void setBalance(Integer balance) {
        this.balance = balance;
    }

    public String getCommodityCode() {
        return commodityCode;
    }

    public void setCommodityCode(String commodityCode) {
        this.commodityCode = commodityCode;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    @Override
    public String toString() {
        return "AccountStockVO{" +
                "userId='" + userId + '\'' +
                ", balance=" + balance +
                ", commodityCode='" + commodityCode + '\'' +
                ", stock=" + stock +
                '}';
    }
}
