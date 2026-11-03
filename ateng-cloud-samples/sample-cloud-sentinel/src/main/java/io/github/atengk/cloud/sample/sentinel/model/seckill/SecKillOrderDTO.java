package io.github.atengk.cloud.sample.sentinel.model.seckill;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 秒杀提单请求传输对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SecKillOrderDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 下单买家用户 ID
     */
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    /**
     * 秒杀商品 ID
     */
    @NotNull(message = "商品 ID 不能为空")
    private Long productId;

    /**
     * 购买数量（单次限购 1~5 件）
     */
    @NotNull(message = "购买数量不能为空")
    @Min(value = 1, message = "秒杀购买数量至少为 1")
    @Max(value = 5, message = "单个用户单次秒杀限购 5 件")
    private Integer count;

    public SecKillOrderDTO() {
    }

    public SecKillOrderDTO(Long userId, Long productId, Integer count) {
        this.userId = userId;
        this.productId = productId;
        this.count = count;
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
}
