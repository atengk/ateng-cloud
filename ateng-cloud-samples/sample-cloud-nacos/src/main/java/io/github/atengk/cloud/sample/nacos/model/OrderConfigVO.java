package io.github.atengk.cloud.sample.nacos.model;

import java.io.Serializable;
import java.time.Duration;

/**
 * 电商订单业务配置响应视图对象（严格遵循 Zero-Entity-Leakage 传输解耦契约）
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class OrderConfigVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Double freightRate;
    private Double freeFreightThreshold;
    private String maskedPayChannelSecretKey;
    private Duration orderTimeout;
    private Integer maxItemsPerOrder;

    public OrderConfigVO() {
    }

    public OrderConfigVO(Double freightRate, Double freeFreightThreshold, String maskedPayChannelSecretKey, Duration orderTimeout, Integer maxItemsPerOrder) {
        this.freightRate = freightRate;
        this.freeFreightThreshold = freeFreightThreshold;
        this.maskedPayChannelSecretKey = maskedPayChannelSecretKey;
        this.orderTimeout = orderTimeout;
        this.maxItemsPerOrder = maxItemsPerOrder;
    }

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

    public String getMaskedPayChannelSecretKey() {
        return maskedPayChannelSecretKey;
    }

    public void setMaskedPayChannelSecretKey(String maskedPayChannelSecretKey) {
        this.maskedPayChannelSecretKey = maskedPayChannelSecretKey;
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
