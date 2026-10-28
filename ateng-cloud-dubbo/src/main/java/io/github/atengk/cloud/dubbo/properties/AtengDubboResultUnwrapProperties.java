package io.github.atengk.cloud.dubbo.properties;

/**
 * Dubbo 双模契约校验与 Result 自动断言配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengDubboResultUnwrapProperties {

    /**
     * 是否开启针对 Result&lt;T&gt; 响应的自动业务成功校验与防呆阻断，默认 true
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
