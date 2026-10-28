package io.github.atengk.cloud.dubbo.properties;

/**
 * Dubbo JSR-303 参数格式校验防卫与结构化转译配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengDubboValidationProperties {

    /**
     * 是否开启 JSR-303 参数格式校验防卫与结构化转译，默认 true
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
