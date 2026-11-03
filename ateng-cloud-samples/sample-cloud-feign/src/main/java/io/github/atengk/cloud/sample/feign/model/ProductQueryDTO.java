package io.github.atengk.cloud.sample.feign.model;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商品复杂查询入参 DTO（用于演示 GET 请求通过 BeanQueryMapEncoder 自动平铺为 URL Query 参数）
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class ProductQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 搜索关键字
     */
    private String keyword;

    /**
     * 商品分类
     */
    private String category;

    /**
     * 最低价格
     */
    private BigDecimal minPrice;

    /**
     * 当前分页页码（从 1 开始）
     */
    private Integer pageNum;

    /**
     * 每页数据量
     */
    private Integer pageSize;

    public ProductQueryDTO() {
    }

    public ProductQueryDTO(String keyword, String category, BigDecimal minPrice, Integer pageNum, Integer pageSize) {
        this.keyword = keyword;
        this.category = category;
        this.minPrice = minPrice;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(BigDecimal minPrice) {
        this.minPrice = minPrice;
    }

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }
}
