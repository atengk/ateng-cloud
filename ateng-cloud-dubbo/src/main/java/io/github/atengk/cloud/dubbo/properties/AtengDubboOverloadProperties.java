package io.github.atengk.cloud.dubbo.properties;

/**
 * Dubbo 服务端线程池过载保护与指标监控配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengDubboOverloadProperties {

    /**
     * 是否开启服务端线程池过载保护，默认 true
     */
    private boolean enabled = true;

    /**
     * 是否向 Micrometer 注册 Dubbo 核心线程池状态与过载拒绝指标，默认 true
     */
    private boolean metricsEnabled = true;

    /**
     * 服务端过载保护定长业务线程池大小，默认 200
     */
    private Integer threads = 200;

    /**
     * 服务端线程池阻塞队列容量，默认 0 (零队列快失败，避免请求长期积压拖垮服务端)
     */
    private Integer queues = 0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    public Integer getThreads() {
        return threads;
    }

    public void setThreads(Integer threads) {
        this.threads = threads;
    }

    public Integer getQueues() {
        return queues;
    }

    public void setQueues(Integer queues) {
        this.queues = queues;
    }
}
