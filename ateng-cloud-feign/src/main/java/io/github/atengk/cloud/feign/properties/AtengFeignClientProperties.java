package io.github.atengk.cloud.feign.properties;

import feign.Logger;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

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
}
