package io.github.atengk.cloud.sample.nacos.model;

import java.io.Serializable;

/**
 * 受管配置监听器触发状态统计视图对象
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class ListenerStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * SPI 监听器累计触发次数
     */
    private int spiChangeCount;

    /**
     * Spring 事件监听器累计触发次数
     */
    private int eventListenerCount;

    /**
     * 最近一次 SPI 监听回调捕获的链路 traceId
     */
    private String lastSpiTraceId;

    /**
     * 最近一次 Spring 事件回调捕获的链路 traceId
     */
    private String lastEventTraceId;

    public ListenerStatusVO() {
    }

    public ListenerStatusVO(int spiChangeCount, int eventListenerCount, String lastSpiTraceId, String lastEventTraceId) {
        this.spiChangeCount = spiChangeCount;
        this.eventListenerCount = eventListenerCount;
        this.lastSpiTraceId = lastSpiTraceId;
        this.lastEventTraceId = lastEventTraceId;
    }

    public int getSpiChangeCount() {
        return spiChangeCount;
    }

    public void setSpiChangeCount(int spiChangeCount) {
        this.spiChangeCount = spiChangeCount;
    }

    public int getEventListenerCount() {
        return eventListenerCount;
    }

    public void setEventListenerCount(int eventListenerCount) {
        this.eventListenerCount = eventListenerCount;
    }

    public String getLastSpiTraceId() {
        return lastSpiTraceId;
    }

    public void setLastSpiTraceId(String lastSpiTraceId) {
        this.lastSpiTraceId = lastSpiTraceId;
    }

    public String getLastEventTraceId() {
        return lastEventTraceId;
    }

    public void setLastEventTraceId(String lastEventTraceId) {
        this.lastEventTraceId = lastEventTraceId;
    }
}
