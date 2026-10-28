package io.github.atengk.cloud.dubbo.properties;

/**
 * Dubbo 双模契约校验与业务断言配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengDubboContractProperties {

    /**
     * 是否开启消费端双模契约校验（非 200 结果自动断言并转译为 BusinessException），默认 true
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
