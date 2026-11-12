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

    /**
     * 当方法声明返回 {@code Result<T>} 且对端返回值为 {@code null} 时，是否自动断言拦截并转译为 BusinessException，默认 true
     */
    private boolean assertNonNullResult = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAssertNonNullResult() {
        return assertNonNullResult;
    }

    public void setAssertNonNullResult(boolean assertNonNullResult) {
        this.assertNonNullResult = assertNonNullResult;
    }
}
