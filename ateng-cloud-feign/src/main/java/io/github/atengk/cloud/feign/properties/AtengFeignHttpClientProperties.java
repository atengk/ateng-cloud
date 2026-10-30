package io.github.atengk.cloud.feign.properties;

import java.time.Duration;

/**
 * Apache HttpClient 5 高并发连接池传输配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignHttpClientProperties {

    /**
     * 是否启用 HC5 连接池传输底座，默认 true
     */
    private boolean enabled = true;

    /**
     * 全局最大连接数上限，默认 200
     */
    private int maxConnections = 200;

    /**
     * 每个路由（目标微服务）的最大并发连接数上限，默认 50
     */
    private int maxConnectionsPerRoute = 50;

    /**
     * 连接最大存活时间（TTL），默认 900 秒
     */
    private Duration timeToLive = Duration.ofSeconds(900);

    /**
     * 网络连接握手超时时间，默认 2000 毫秒
     */
    private Duration connectionTimeout = Duration.ofMillis(2000);

    /**
     * 从连接池获取连接等待超时时间，默认 3000 毫秒
     */
    private Duration connectionRequestTimeout = Duration.ofMillis(3000);

    /**
     * 数据套接字读取超时时间，默认 5000 毫秒
     */
    private Duration socketTimeout = Duration.ofMillis(5000);

    /**
     * 连接池空闲物理连接最大存活时限，超过该时间未被使用的连接将被后台线程主动驱逐，默认 60 秒
     */
    private Duration maxIdleTime = Duration.ofSeconds(60);

    /**
     * 后台线程主动扫描并驱逐空闲与失效连接的时间间隔，默认 60 秒
     */
    private Duration evictIdleConnectionsInterval = Duration.ofSeconds(60);

    /**
     * 是否在后台主动定时驱逐超过总存活时限（TTL）的失效物理连接，默认 true
     */
    private boolean evictExpiredConnections = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMaxConnectionsPerRoute() {
        return maxConnectionsPerRoute;
    }

    public void setMaxConnectionsPerRoute(int maxConnectionsPerRoute) {
        this.maxConnectionsPerRoute = maxConnectionsPerRoute;
    }

    public Duration getTimeToLive() {
        return timeToLive;
    }

    public void setTimeToLive(Duration timeToLive) {
        this.timeToLive = timeToLive;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Duration getConnectionRequestTimeout() {
        return connectionRequestTimeout;
    }

    public void setConnectionRequestTimeout(Duration connectionRequestTimeout) {
        this.connectionRequestTimeout = connectionRequestTimeout;
    }

    public Duration getSocketTimeout() {
        return socketTimeout;
    }

    public void setSocketTimeout(Duration socketTimeout) {
        this.socketTimeout = socketTimeout;
    }

    public Duration getMaxIdleTime() {
        return maxIdleTime;
    }

    public void setMaxIdleTime(Duration maxIdleTime) {
        this.maxIdleTime = maxIdleTime;
    }

    public Duration getEvictIdleConnectionsInterval() {
        return evictIdleConnectionsInterval;
    }

    public void setEvictIdleConnectionsInterval(Duration evictIdleConnectionsInterval) {
        this.evictIdleConnectionsInterval = evictIdleConnectionsInterval;
    }

    public boolean isEvictExpiredConnections() {
        return evictExpiredConnections;
    }

    public void setEvictExpiredConnections(boolean evictExpiredConnections) {
        this.evictExpiredConnections = evictExpiredConnections;
    }
}
