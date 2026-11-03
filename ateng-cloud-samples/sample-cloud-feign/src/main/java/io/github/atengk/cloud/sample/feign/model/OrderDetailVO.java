package io.github.atengk.cloud.sample.feign.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单详情视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class OrderDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    private String orderNo;

    /**
     * 买家用户 ID（透传自上下文）
     */
    private String buyerId;

    /**
     * 归属多租户 ID（透传自上下文）
     */
    private String tenantId;

    /**
     * 购买商品详情
     */
    private ProductDetailVO product;

    /**
     * 购买数量
     */
    private Integer quantity;

    /**
     * 订单实付总金额
     */
    private BigDecimal totalAmount;

    /**
     * 全链路分布式追踪 TraceId
     */
    private String traceId;

    /**
     * 金丝雀灰度流量染色标记
     */
    private String trafficTag;

    /**
     * 内部受信 Same-Token 签名凭证
     */
    private String sameToken;

    /**
     * 订单创建时间
     */
    private LocalDateTime createTime;

    public OrderDetailVO() {
    }

    public OrderDetailVO(String orderNo, String buyerId, String tenantId, ProductDetailVO product,
                         Integer quantity, BigDecimal totalAmount, String traceId,
                         String trafficTag, String sameToken, LocalDateTime createTime) {
        this.orderNo = orderNo;
        this.buyerId = buyerId;
        this.tenantId = tenantId;
        this.product = product;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
        this.traceId = traceId;
        this.trafficTag = trafficTag;
        this.sameToken = sameToken;
        this.createTime = createTime;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(String buyerId) {
        this.buyerId = buyerId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public ProductDetailVO getProduct() {
        return product;
    }

    public void setProduct(ProductDetailVO product) {
        this.product = product;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getTrafficTag() {
        return trafficTag;
    }

    public void setTrafficTag(String trafficTag) {
        this.trafficTag = trafficTag;
    }

    public String getSameToken() {
        return sameToken;
    }

    public void setSameToken(String sameToken) {
        this.sameToken = sameToken;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
