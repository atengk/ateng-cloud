package io.github.atengk.cloud.sample.sentinel.model.seckill;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 秒杀专享商品详情视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SecKillProductVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商品 ID
     */
    private Long productId;

    /**
     * 商品名称
     */
    private String productName;

    /**
     * 秒杀专享价格
     */
    private BigDecimal price;

    /**
     * 当前秒杀剩余库存
     */
    private Integer stock;

    /**
     * 商品状态 (AVAILABLE / SOLD_OUT)
     */
    private String status;

    public SecKillProductVO() {
    }

    public SecKillProductVO(Long productId, String productName, BigDecimal price, Integer stock, String status) {
        this.productId = productId;
        this.productName = productName;
        this.price = price;
        this.stock = stock;
        this.status = status;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
