package io.github.atengk.cloud.feign.properties;

import feign.Logger;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 单 Client 独立隔离配置属性
 * 支持特定下游微服务物理隔离其独立的超时与重试参数，未配置项平滑回退至全局基线
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignClientProperties {

    /**
     * 建立连接超时时间
     */
    private Duration connectTimeout;

    /**
     * 网络读取/响应超时时间
     */
    private Duration readTimeout;

    /**
     * Client 级独立的弹性重试配置
     */
    @NestedConfigurationProperty
    private AtengFeignRetryProperties retry;

    /**
     * Client 级独立的日志打印级别
     */
    private Logger.Level loggerLevel;

    /**
     * Client 级是否开启自适应契约解包（未配置则遵循全局规则与注解）
     */
    private Boolean adaptiveUnwrap;

    /**
     * Client 级独立的响应报文大小硬防爆阈值（强类型 DataSize，未配置则回退全局 payload.max-response-size）
     */
    private DataSize maxResponseSize;

    /**
     * Client 级独立的慢调用告警阈值（强类型 Duration，设为 0 或负数可关闭单客户端慢调用告警，未配置则回退全局 slow-call-threshold）
     */
    private Duration slowCallThreshold;

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /**
     * 别名方法：获取网络读取超时时间，等价于 readTimeout
     *
     * @return 读取超时时间
     */
    public Duration getSocketTimeout() {
        return readTimeout;
    }

    /**
     * 别名方法：设置网络读取超时时间，映射至 readTimeout
     *
     * @param socketTimeout 读取超时时间
     */
    public void setSocketTimeout(Duration socketTimeout) {
        this.readTimeout = socketTimeout;
    }

    public AtengFeignRetryProperties getRetry() {
        return retry;
    }

    public void setRetry(AtengFeignRetryProperties retry) {
        this.retry = retry;
    }

    public Logger.Level getLoggerLevel() {
        return loggerLevel;
    }

    public void setLoggerLevel(Logger.Level loggerLevel) {
        this.loggerLevel = loggerLevel;
    }

    public Boolean getAdaptiveUnwrap() {
        return adaptiveUnwrap;
    }

    public void setAdaptiveUnwrap(Boolean adaptiveUnwrap) {
        this.adaptiveUnwrap = adaptiveUnwrap;
    }

    /**
     * 获取 Client 级响应报文大小硬防爆阈值
     *
     * @return 响应防爆阈值（未配置则返回 null）
     */
    public DataSize getMaxResponseSize() {
        return maxResponseSize;
    }

    /**
     * 设置 Client 级响应报文大小硬防爆阈值
     *
     * @param maxResponseSize 响应防爆阈值
     */
    public void setMaxResponseSize(DataSize maxResponseSize) {
        this.maxResponseSize = maxResponseSize;
    }

    /**
     * 获取 Client 级慢调用告警阈值
     *
     * @return 慢调用告警阈值（未配置则返回 null）
     */
    public Duration getSlowCallThreshold() {
        return slowCallThreshold;
    }

    /**
     * 设置 Client 级慢调用告警阈值
     *
     * @param slowCallThreshold 慢调用告警阈值（0 或负数可单独关闭）
     */
    public void setSlowCallThreshold(Duration slowCallThreshold) {
        this.slowCallThreshold = slowCallThreshold;
    }
}
