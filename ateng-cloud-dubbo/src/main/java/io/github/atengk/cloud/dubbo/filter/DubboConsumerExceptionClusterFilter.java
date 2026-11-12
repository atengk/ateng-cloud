package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.support.DubboAttachmentSupport;
import io.github.atengk.cloud.dubbo.support.DubboExceptionUtils;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.BaseFilter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.filter.ClusterFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dubbo 消费端集群层原生 RpcException 按需因果转译过滤器
 * <p>
 * 运行于 {@code MockClusterInvoker} 内层、{@code FailoverClusterInvoker} 外层，仅在
 * {@code ateng.cloud.dubbo.exception.translate-consumer-rpc-exception=true} 且当前 {@link Invoker}
 * 未配置有效原生 {@code mock} 参数时激活：
 * 1. 超时异常 ({@code TIMEOUT_EXCEPTION}) -> {@code 504 GATEWAY_TIMEOUT}；
 * 2. 网络异常 / 禁止访问 / 无可用提供者 -> {@code 503 SERVICE_UNAVAILABLE}；
 * 3. 其他未知 {@link RpcException} -> {@code 500 INTERNAL_SERVER_ERROR}；
 * 4. 100% 保留原始 {@link RpcException} 作为 {@code cause}，并在已配置 {@code mock} 时无条件放行交由外层执行原生降级。
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Activate(group = CommonConstants.CONSUMER, order = -9500)
public class DubboConsumerExceptionClusterFilter implements ClusterFilter, BaseFilter.Listener {

    private static final Logger log = LoggerFactory.getLogger(DubboConsumerExceptionClusterFilter.class);

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        if (!shouldTranslate(invoker, invocation)) {
            return invoker.invoke(invocation);
        }

        try {
            Result result = invoker.invoke(invocation);
            if (result != null && result.hasException() && result.getException() instanceof RpcException rpcException) {
                BusinessException translated = resolveTranslatedException(rpcException, invoker, invocation);
                return AsyncRpcResult.newDefaultAsyncResult(translated, invocation);
            }
            return result;
        } catch (RpcException rpcException) {
            throw resolveTranslatedException(rpcException, invoker, invocation);
        }
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        if (appResponse == null || !appResponse.hasException()) {
            return;
        }
        if (!(appResponse.getException() instanceof RpcException rpcException)) {
            return;
        }
        if (!shouldTranslate(invoker, invocation)) {
            return;
        }
        BusinessException translated = resolveTranslatedException(rpcException, invoker, invocation);
        appResponse.setException(translated);
        appResponse.setValue(null);
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        // 同步与异步异常主流程已在 invoke / onResponse 中受控处理
    }

    private boolean shouldTranslate(Invoker<?> invoker, Invocation invocation) {
        var properties = DubboPropertiesHolder.get();
        if (properties == null || !properties.isEnabled() || properties.getException() == null) {
            return false;
        }
        var exceptionProps = properties.getException();
        if (!exceptionProps.isEnabled() || !exceptionProps.isTranslateConsumerRpcException()) {
            return false;
        }
        if (invoker == null
                || DubboAttachmentSupport.isExplicitProviderSide(invoker)
                || DubboExceptionUtils.isStreamInvocation(invocation)
                || DubboExceptionUtils.isGenericInvocation(invoker, invocation)) {
            return false;
        }
        return !DubboExceptionUtils.hasEffectiveMock(invoker, invocation);
    }

    private BusinessException resolveTranslatedException(RpcException rpcException, Invoker<?> invoker, Invocation invocation) {
        // 保留内层 Filter 抛出后被 FailoverClusterInvoker 包装的受管 BusinessException（含 429 过载与契约断言异常）
        if (rpcException.getCause() instanceof BusinessException innerBusinessException) {
            return innerBusinessException;
        }
        BusinessException translated = DubboExceptionUtils.translateConsumerRpcException(rpcException);
        String serviceName = DubboAttachmentSupport.resolveServiceName(invoker);
        String methodName = DubboExceptionUtils.resolveMethodName(invocation);
        log.warn("[DUBBO-CONSUMER-EXCEPTION] 消费端 RpcException 因果转译: code={}, rpcCode={}, service={}, method={}, msg={}",
                translated.getCode(), rpcException.getCode(), serviceName, methodName, rpcException.getMessage());
        return translated;
    }
}
