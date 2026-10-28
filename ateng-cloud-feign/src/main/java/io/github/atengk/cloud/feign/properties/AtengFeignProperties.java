package io.github.atengk.cloud.feign.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Feign 客户端核心治理与通信配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
@ConfigurationProperties(prefix = "ateng.cloud.feign")
public class AtengFeignProperties {

    /**
     * 是否开启 ateng-cloud-feign 核心治理特性，默认 true
     */
    private boolean enabled = true;

    /**
     * Apache HttpClient 5 高并发连接池传输层配置
     */
    @NestedConfigurationProperty
    private AtengFeignHttpClientProperties httpclient = new AtengFeignHttpClientProperties();

    /**
     * 双模自适应契约解包引擎配置
     */
    @NestedConfigurationProperty
    private AtengFeignAdaptiveUnwrapProperties adaptiveUnwrap = new AtengFeignAdaptiveUnwrapProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public AtengFeignHttpClientProperties getHttpclient() {
        return httpclient;
    }

    public void setHttpclient(AtengFeignHttpClientProperties httpclient) {
        this.httpclient = httpclient;
    }

    public AtengFeignAdaptiveUnwrapProperties getAdaptiveUnwrap() {
        return adaptiveUnwrap;
    }

    public void setAdaptiveUnwrap(AtengFeignAdaptiveUnwrapProperties adaptiveUnwrap) {
        this.adaptiveUnwrap = adaptiveUnwrap;
    }
}
