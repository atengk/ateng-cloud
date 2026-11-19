package io.github.atengk.cloud.dubbo.properties;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * 是否开启消费端原生 RpcException 按需因果转译（超时转 504、网络/无提供者转 503、未知 RPC 异常转 500；
     * 若 Invoker 已配置有效 mock 参数则自动放行交由原生 Mock 降级），默认 false 以保持存量原生 RpcException 断言向后兼容
     */
    private boolean translateConsumerRpcException = false;

    /**
     * 转译底层未知/校验/过载异常为 {@code BusinessException} 时，是否自动剥离非白名单包（非 {@code java.*}/{@code javax.*}/{@code jakarta.*}/{@code io.github.atengk.*}）的 {@code cause}，
     * 防止消费端缺少服务端驱动类或被 Dubbo {@code SerializeSecurityManager} 拦截导致反序列化失败（默认 true；服务端仍完整打印原堆栈日志）
     */
    private boolean sanitizeUntrustedCause = true;

    /**
     * 允许在异常转译时保留 {@code cause} 的额外可信包前缀列表（例如 {@code com.company.common.}）
     */
    private List<String> trustedCausePackages = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTranslateConsumerRpcException() {
        return translateConsumerRpcException;
    }

    public void setTranslateConsumerRpcException(boolean translateConsumerRpcException) {
        this.translateConsumerRpcException = translateConsumerRpcException;
    }

    public boolean isSanitizeUntrustedCause() {
        return sanitizeUntrustedCause;
    }

    public void setSanitizeUntrustedCause(boolean sanitizeUntrustedCause) {
        this.sanitizeUntrustedCause = sanitizeUntrustedCause;
    }

    public List<String> getTrustedCausePackages() {
        return trustedCausePackages;
    }

    public void setTrustedCausePackages(List<String> trustedCausePackages) {
        this.trustedCausePackages = trustedCausePackages != null ? new ArrayList<>(trustedCausePackages) : new ArrayList<>();
    }
}
