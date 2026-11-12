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

    /**
     * 是否开启消费端原生 RpcException 按需因果转译（超时转 504、网络/无提供者转 503、未知 RPC 异常转 500；
     * 若 Invoker 已配置有效 mock 参数则自动放行交由原生 Mock 降级），默认 false 以保持存量原生 RpcException 断言向后兼容
     */
    private boolean translateConsumerRpcException = false;

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
}
