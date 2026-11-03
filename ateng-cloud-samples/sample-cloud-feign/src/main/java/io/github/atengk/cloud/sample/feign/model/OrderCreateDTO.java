package io.github.atengk.cloud.sample.feign.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 订单创建请求 DTO（挂载 JSR-303 格式校验）
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class OrderCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 购买商品 ID
     */
    @NotNull(message = "商品 ID 不可为空")
    private Long productId;

    /**
     * 购买数量
     */
    @NotNull(message = "购买数量不可为空")
    @Min(value = 1, message = "购买数量至少为 1")
    private Integer quantity;

    /**
     * 收货地址
     */
    @NotBlank(message = "收货地址不可为空")
    private String deliveryAddress;

    /**
     * 客户备注
     */
    private String remark;

    public OrderCreateDTO() {
    }

    public OrderCreateDTO(Long productId, Integer quantity, String deliveryAddress, String remark) {
        this.productId = productId;
        this.quantity = quantity;
        this.deliveryAddress = deliveryAddress;
        this.remark = remark;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public void setDeliveryAddress(String deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
