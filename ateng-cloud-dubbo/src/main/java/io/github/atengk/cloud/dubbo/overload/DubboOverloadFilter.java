package io.github.atengk.cloud.dubbo.overload;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.BaseFilter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;

/**
 * Dubbo 服务端线程池过载保护与异常转译过滤器
 * <p>
 * 当 RPC 执行链路中发生线程池耗尽拒绝 ({@link RejectedExecutionException} 或包含 "Thread pool is EXHAUSTED") 时，
 * 安全转译为标准受管 {@link BusinessException} (TOO_MANY_REQUESTS / 429)，杜绝原始堆栈穿透污染并支持网关与客户端优雅熔断限流。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate(group = {CommonConstants.PROVIDER, CommonConstants.CONSUMER}, order = -8900)
public class DubboOverloadFilter implements Filter, BaseFilter.Listener {

    private static final Logger log = LoggerFactory.getLogger(DubboOverloadFilter.class);

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        var properties = DubboPropertiesHolder.get();
        if (properties != null && properties.getOverload() != null && !properties.getOverload().isEnabled()) {
            return invoker.invoke(invocation);
        }

        Result result;
        try {
            result = invoker.invoke(invocation);
        } catch (RpcException e) {
            if (isOverloadException(e)) {
                DubboOverloadListener.incrementRejectCount();
                log.warn("[DUBBO-OVERLOAD] 同步捕获线程池过载 RpcException: service={}, method={}, error={}",
                        invoker.getInterface().getName(), invocation.getMethodName(), e.getMessage());
                throw new BusinessException(
                        CommonResultCode.TOO_MANY_REQUESTS.getCode(),
                        "服务端并发线程池已耗尽，触发过载保护限流",
                        e
                );
            }
            throw e;
        }

        if (result.hasException() && isOverloadException(result.getException())) {
            DubboOverloadListener.incrementRejectCount();
            log.warn("[DUBBO-OVERLOAD] invoke 返回结果捕获线程池过载异常: service={}, method={}, error={}",
                    invoker.getInterface().getName(), invocation.getMethodName(), result.getException().getMessage());
            BusinessException be = new BusinessException(
                    CommonResultCode.TOO_MANY_REQUESTS.getCode(),
                    "服务端并发线程池已耗尽，触发过载保护限流",
                    result.getException()
            );
            return AsyncRpcResult.newDefaultAsyncResult(be, invocation);
        }

        return result;
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        var properties = DubboPropertiesHolder.get();
        if (properties != null && properties.getOverload() != null && !properties.getOverload().isEnabled()) {
            return;
        }

        if (!appResponse.hasException()) {
            return;
        }

        Throwable exception = appResponse.getException();
        if (isOverloadException(exception) && !(exception instanceof BusinessException)) {
            DubboOverloadListener.incrementRejectCount();
            log.warn("[DUBBO-OVERLOAD] onResponse 捕获线程池过载异常: service={}, method={}, error={}",
                    invoker.getInterface().getName(), invocation.getMethodName(), exception.getMessage());
            BusinessException businessException = new BusinessException(
                    CommonResultCode.TOO_MANY_REQUESTS.getCode(),
                    "服务端并发线程池已耗尽，触发过载保护限流",
                    exception
            );
            appResponse.setException(businessException);
            appResponse.setValue(null);
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        if (isOverloadException(t)) {
            DubboOverloadListener.incrementRejectCount();
            log.warn("[DUBBO-OVERLOAD] 调用 onError 捕获线程池过载: service={}, method={}, error={}",
                    invoker.getInterface().getName(), invocation.getMethodName(), t.getMessage());
        }
    }

    private boolean isOverloadException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RejectedExecutionException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("thread pool is exhausted")
                        || lower.contains("threadpool is exhausted")
                        || lower.contains("threads can not be used anymore")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
