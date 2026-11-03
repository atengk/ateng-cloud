package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;

/**
 * 商品库存查询响应视图 (用于读请求重试场景)
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class InventoryStockVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商品 SKU 编码
     */
    private String skuCode;

    /**
     * 当前可用库存
     */
    private int availableStock;

    /**
     * 底层实际尝试调用次数
     */
    private int attemptCount;

    /**
     * 响应执行状态
     */
    private String status;

    public InventoryStockVO() {
    }

    public InventoryStockVO(String skuCode, int availableStock, int attemptCount, String status) {
        this.skuCode = skuCode;
        this.availableStock = availableStock;
        this.attemptCount = attemptCount;
        this.status = status;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public int getAvailableStock() {
        return availableStock;
    }

    public void setAvailableStock(int availableStock) {
        this.availableStock = availableStock;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
