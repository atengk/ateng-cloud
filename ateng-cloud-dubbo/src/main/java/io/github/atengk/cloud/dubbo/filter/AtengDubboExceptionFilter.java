package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.overload.DubboOverloadListener;
import io.github.atengk.cloud.dubbo.support.DubboExceptionUtils;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.BaseFilter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.RpcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * Dubbo 服务端因果异常保真与反序列化防御过滤器
 * <p>
 * 接管 Dubbo 默认 ExceptionFilter 的未受检异常抹平行为：
 * 1. 白名单放行受管 {@link BusinessException}、JSR-303 参数校验异常及 JDK 核心异常，100% 完整保留原始错误码、错误信息与因果链；
 * 2. 对调用方类路径可能缺失的底层未知系统崩溃异常（如第三方数据库驱动异常），安全转译为标准 {@link BusinessException}，
 *    彻底防御消费端发生 {@link ClassNotFoundException} 反序列化灾难。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate(group = CommonConstants.PROVIDER)
public class AtengDubboExceptionFilter implements Filter, BaseFilter.Listener {

    private static final Logger log = LoggerFactory.getLogger(AtengDubboExceptionFilter.class);

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        return invoker.invoke(invocation);
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        var properties = DubboPropertiesHolder.get();
        if (properties != null && (!properties.isEnabled()
                || (properties.getException() != null && !properties.getException().isEnabled()))) {
            return;
        }

        if (!appResponse.hasException() || GenericService.class == invoker.getInterface()) {
            return;
        }

        Throwable exception = appResponse.getException();

        // 1. 受管 BusinessException：白名单直接放行，完整保留错误码、描述与原因链
        if (exception instanceof BusinessException) {
            return;
        }

        // 2. JSR-303 参数校验异常：结构化转译为标准受管 BusinessException(BAD_REQUEST)
        ConstraintViolationException cve = DubboExceptionUtils.findConstraintViolationException(exception);
        if (cve != null) {
            String errorMsg = DubboExceptionUtils.formatConstraintViolations(cve.getConstraintViolations());
            log.warn("[DUBBO] Provider 拦截 JSR-303 参数校验失败: service={}, method={}, error={}",
                    invoker.getInterface().getName(), RpcUtils.getMethodName(invocation), errorMsg);
            BusinessException be = new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), errorMsg, cve);
            appResponse.setException(be);
            return;
        }

        // 3. 服务端线程池过载拒绝异常：转译为受管 BusinessException(TOO_MANY_REQUESTS) 并记录过载诊断
        if (DubboExceptionUtils.isOverloadException(exception)) {
            DubboOverloadListener.incrementRejectCount();
            log.warn("[DUBBO-OVERLOAD] Provider 拦截线程池耗尽过载异常: service={}, method={}, error={}",
                    invoker.getInterface().getName(), RpcUtils.getMethodName(invocation), exception.getMessage());
            BusinessException be = new BusinessException(
                    CommonResultCode.TOO_MANY_REQUESTS.getCode(),
                    "服务端并发线程池已耗尽，触发过载保护限流",
                    exception
            );
            appResponse.setException(be);
            return;
        }

        // 4. 受检异常 (Checked Exception，非 RuntimeException 且属于 Exception)：直接放行
        if (!(exception instanceof RuntimeException) && (exception instanceof Exception)) {
            return;
        }

        // 5. 方法签名 throws 中已显式声明的异常类型：直接放行
        try {
            Method method = invoker.getInterface().getMethod(RpcUtils.getMethodName(invocation), invocation.getParameterTypes());
            Class<?>[] exceptionClasses = method.getExceptionTypes();
            for (Class<?> exceptionClass : exceptionClasses) {
                if (exceptionClass.isAssignableFrom(exception.getClass())) {
                    return;
                }
            }
        } catch (NoSuchMethodException e) {
            log.debug("[DUBBO] Provider 未能在接口中定位目标方法签名: method={}, error={}",
                    RpcUtils.getMethodName(invocation), e.getMessage());
        }

        // 6. JDK 标准核心异常 (java.*, javax.*, jakarta.*)：直接放行
        String className = exception.getClass().getName();
        if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jakarta.")) {
            return;
        }

        // 7. Dubbo 原生受管 RpcException：直接放行
        if (exception instanceof RpcException) {
            return;
        }

        // 8. 未受检的第三方底层私有异常：安全转译为框架标准受管 BusinessException，保留原始因果链与错误消息
        log.warn("[DUBBO] Provider 未声明未受检异常转译: service={}, method={}, errorClass={}, errorMsg={}",
                invoker.getInterface().getName(), RpcUtils.getMethodName(invocation), className, exception.getMessage());
        String errorMsg = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
        BusinessException safeException = new BusinessException(
                CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                "RPC 底层调用未知异常: " + errorMsg,
                exception
        );
        appResponse.setException(safeException);
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        log.error("[DUBBO] Provider 执行发生系统调用错误: service={}, method={}, error={}",
                invoker.getInterface().getName(), RpcUtils.getMethodName(invocation), t.getMessage(), t);
    }
}
