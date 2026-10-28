package io.github.atengk.cloud.feign.properties;

/**
 * Feign 全链路四维上下文透传与零信任防穿透配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignContextProperties {

    /**
     * 是否开启出站上下文透传拦截器（包括 traceId、userId、tenantId、trafficTag 与 Same-Token），默认 true
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
