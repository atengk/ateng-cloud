package io.github.atengk.cloud.sample.feign.model;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商品详情视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class ProductDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商品 ID
     */
    private Long id;

    /**
     * 商品 SKU 编码
     */
    private String skuCode;

    /**
     * 商品标题
     */
    private String title;

    /**
     * 商品单价（元）
     */
    private BigDecimal price;

    /**
     * 可售库存数量
     */
    private Integer stock;

    /**
     * 商品状态 (1: 上架中, 0: 已下架)
     */
    private Integer status;

    public ProductDetailVO() {
    }

    public ProductDetailVO(Long id, String skuCode, String title, BigDecimal price, Integer stock, Integer status) {
        this.id = id;
        this.skuCode = skuCode;
        this.title = title;
        this.price = price;
        this.stock = stock;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
