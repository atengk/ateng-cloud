package io.github.atengk.cloud.sample.sentinel.model.seckill;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀提单响应结果视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SecKillResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 秒杀生成的唯一订单流水号
     */
    private String orderNo;

    /**
     * 买家用户 ID
     */
    private Long userId;

    /**
     * 秒杀商品 ID
     */
    private Long productId;

    /**
     * 购买数量
     */
    private Integer count;

    /**
     * 秒杀订单业务状态
     */
    private String status;

    /**
     * 提单创建时间
     */
    private LocalDateTime createTime;

    public SecKillResultVO() {
    }

    public SecKillResultVO(String orderNo, Long userId, Long productId, Integer count, String status, LocalDateTime createTime) {
        this.orderNo = orderNo;
        this.userId = userId;
        this.productId = productId;
        this.count = count;
        this.status = status;
        this.createTime = createTime;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
