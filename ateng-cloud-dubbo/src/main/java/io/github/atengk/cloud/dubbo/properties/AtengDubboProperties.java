package io.github.atengk.cloud.dubbo.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Dubbo 核心通信与治理配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
@ConfigurationProperties(prefix = "ateng.cloud.dubbo")
public class AtengDubboProperties {

    /**
     * 是否开启 ateng-cloud-dubbo 核心治理特性，默认 true
     */
    private boolean enabled = true;

    /**
     * 全局 RPC 默认超时时间，生产安全基线默认 5000ms (5秒)
     */
    private Duration timeout = Duration.ofMillis(5000);

    /**
     * 全局 RPC 默认重试次数，生产防雪崩安全基线默认 0 (写请求绝不盲目重试)
     */
    private int retries = 0;

    /**
     * 单个 RPC 请求/响应报文载荷硬防爆上限，默认 16MB
     */
    private DataSize payload = DataSize.ofMegabytes(16);

    /**
     * 默认通信协议配置
     */
    @NestedConfigurationProperty
    private AtengDubboProtocolProperties protocol = new AtengDubboProtocolProperties();

    /**
     * 应用层服务发现模式配置
     */
    @NestedConfigurationProperty
    private AtengDubboApplicationProperties application = new AtengDubboApplicationProperties();

    /**
     * 全链路四维上下文透传配置
     */
    @NestedConfigurationProperty
    private AtengDubboContextProperties context = new AtengDubboContextProperties();

    /**
     * 零信任 Same-Token 内部防穿透配置
     */
    @NestedConfigurationProperty
    private AtengDubboSameTokenProperties sameToken = new AtengDubboSameTokenProperties();

    /**
     * 因果异常保真与反序列化防御配置
     */
    @NestedConfigurationProperty
    private AtengDubboExceptionProperties exception = new AtengDubboExceptionProperties();

    /**
     * 双模契约校验与业务结果自动断言配置
     */
    @NestedConfigurationProperty
    private AtengDubboContractProperties contract = new AtengDubboContractProperties();

    /**
     * 金丝雀灰度流量染色与标签路由配置
     */
    @NestedConfigurationProperty
    private AtengDubboTrafficProperties traffic = new AtengDubboTrafficProperties();

    /**
     * 服务端线程池过载保护与指标监控配置
     */
    @NestedConfigurationProperty
    private AtengDubboOverloadProperties overload = new AtengDubboOverloadProperties();

    /**
     * JSR-303 参数格式校验防卫与结构化转译配置
     */
    @NestedConfigurationProperty
    private AtengDubboValidationProperties validation = new AtengDubboValidationProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public DataSize getPayload() {
        return payload;
    }

    public void setPayload(DataSize payload) {
        this.payload = payload;
    }

    public AtengDubboProtocolProperties getProtocol() {
        return protocol;
    }

    public void setProtocol(AtengDubboProtocolProperties protocol) {
        this.protocol = protocol;
    }

    public AtengDubboApplicationProperties getApplication() {
        return application;
    }

    public void setApplication(AtengDubboApplicationProperties application) {
        this.application = application;
    }

    public AtengDubboContextProperties getContext() {
        return context;
    }

    public void setContext(AtengDubboContextProperties context) {
        this.context = context;
    }

    public AtengDubboSameTokenProperties getSameToken() {
        return sameToken;
    }

    public void setSameToken(AtengDubboSameTokenProperties sameToken) {
        this.sameToken = sameToken;
    }

    public AtengDubboExceptionProperties getException() {
        return exception;
    }

    public void setException(AtengDubboExceptionProperties exception) {
        this.exception = exception;
    }

    public AtengDubboContractProperties getContract() {
        return contract;
    }

    public void setContract(AtengDubboContractProperties contract) {
        this.contract = contract;
    }

    @Deprecated(since = "1.0.0", forRemoval = false)
    public AtengDubboContractProperties getResultUnwrap() {
        return contract;
    }

    @Deprecated(since = "1.0.0", forRemoval = false)
    public void setResultUnwrap(AtengDubboContractProperties resultUnwrap) {
        this.contract = resultUnwrap;
    }

    public AtengDubboTrafficProperties getTraffic() {
        return traffic;
    }

    public void setTraffic(AtengDubboTrafficProperties traffic) {
        this.traffic = traffic;
    }

    public AtengDubboOverloadProperties getOverload() {
        return overload;
    }

    public void setOverload(AtengDubboOverloadProperties overload) {
        this.overload = overload;
    }

    public AtengDubboValidationProperties getValidation() {
        return validation;
    }

    public void setValidation(AtengDubboValidationProperties validation) {
        this.validation = validation;
    }
}
