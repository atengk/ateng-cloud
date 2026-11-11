package io.github.atengk.cloud.sample.nacos.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 电商订单业务配置属性 POJO（演示单例不可变性与属性增量热绑）
 *
 * <p>微服务铁律：业务配置通过 {@link ConfigurationProperties} 声明，热更时仅增量重新绑定字段，
 * 严禁在业务 Service 或 Component 上标注 {@code @RefreshScope}。</p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Component
@ConfigurationProperties(prefix = "sample.order.config")
public class OrderConfigProperties {

    /**
     * 基础订单运费（元）
     */
    @NotNull(message = "freightRate must not be null")
    @PositiveOrZero(message = "freightRate must be zero or positive")
    private Double freightRate = 10.0;

    /**
     * 满额包邮门槛（元）
     */
    @NotNull(message = "freeFreightThreshold must not be null")
    @PositiveOrZero(message = "freeFreightThreshold must be zero or positive")
    private Double freeFreightThreshold = 99.0;

    /**
     * 第三方支付通道通信加密密钥（演示 ENC(...) 密文解析、脱敏屏障与内存真明文）
     */
    private String payChannelSecretKey = "";

    /**
     * 未支付订单超时自动关闭时限（强类型 Duration 规范）
     */
    @NotNull(message = "orderTimeout must not be null")
    private Duration orderTimeout = Duration.ofMinutes(30);

    /**
     * 单笔订单最大购买商品件数上限
     */
    @NotNull(message = "maxItemsPerOrder must not be null")
    @Min(value = 1, message = "maxItemsPerOrder must be at least 1")
    private Integer maxItemsPerOrder = 50;

    public Double getFreightRate() {
        return freightRate;
    }

    public void setFreightRate(Double freightRate) {
        this.freightRate = freightRate;
    }

    public Double getFreeFreightThreshold() {
        return freeFreightThreshold;
    }

    public void setFreeFreightThreshold(Double freeFreightThreshold) {
        this.freeFreightThreshold = freeFreightThreshold;
    }

    public String getPayChannelSecretKey() {
        return payChannelSecretKey;
    }

    public void setPayChannelSecretKey(String payChannelSecretKey) {
        this.payChannelSecretKey = payChannelSecretKey;
    }

    public Duration getOrderTimeout() {
        return orderTimeout;
    }

    public void setOrderTimeout(Duration orderTimeout) {
        this.orderTimeout = orderTimeout;
    }

    public Integer getMaxItemsPerOrder() {
        return maxItemsPerOrder;
    }

    public void setMaxItemsPerOrder(Integer maxItemsPerOrder) {
        this.maxItemsPerOrder = maxItemsPerOrder;
    }
}
