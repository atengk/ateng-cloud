package io.github.atengk.cloud.dubbo.properties;

/**
 * Dubbo 因果异常保真与反序列化防御配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengDubboExceptionProperties {

    /**
     * 是否启用定制异常过滤器（白名单放行受管业务异常，底层异常因果转译），默认 true
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
