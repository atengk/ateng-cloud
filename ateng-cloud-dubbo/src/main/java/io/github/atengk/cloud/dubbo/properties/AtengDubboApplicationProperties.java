package io.github.atengk.cloud.dubbo.properties;

import java.time.Duration;

/**
 * Dubbo 应用层服务发现模式配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengDubboApplicationProperties {

    /**
     * 服务注册与发现模式，默认 "instance" (应用级服务发现，对齐 Spring Cloud 与 Nacos 规范，彻底避免注册表膨胀)
     */
    private String registerMode = "instance";

    /**
     * 优雅停机等待超时时间，默认 15000ms (15秒超时硬兜底，排空在途请求再平稳下线)
     */
    private Duration shutWait = Duration.ofMillis(15000);

    public String getRegisterMode() {
        return registerMode;
    }

    public void setRegisterMode(String registerMode) {
        this.registerMode = registerMode;
    }

    public Duration getShutWait() {
        return shutWait;
    }

    public void setShutWait(Duration shutWait) {
        this.shutWait = shutWait;
    }
}
