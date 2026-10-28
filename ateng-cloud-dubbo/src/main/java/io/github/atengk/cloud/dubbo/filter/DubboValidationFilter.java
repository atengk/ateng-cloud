package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.cloud.dubbo.support.DubboExceptionUtils;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.BaseFilter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Dubbo 服务端 JSR-303 参数格式快速失败防卫与转译过滤器
 * <p>
 * 1. 在 Provider 边界入站时，基于 {@link ValidatorUtil} 对非基础类型入参执行 Fail-Fast 校验，
 *    一旦存在违规字段立即阻断请求向下游 Service 穿透；
 * 2. 拦截并转译 {@link ConstraintViolationException}，提取具体违规字段路径与错误描述，
 *    统一结构化转译为框架标准受管 {@link BusinessException} (BAD_REQUEST / 400)。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate(group = CommonConstants.PROVIDER, order = -9000)
public class DubboValidationFilter implements Filter, BaseFilter.Listener {

    private static final Logger log = LoggerFactory.getLogger(DubboValidationFilter.class);

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        var properties = DubboPropertiesHolder.get();
        if (properties != null && (!properties.isEnabled()
                || (properties.getValidation() != null && !properties.getValidation().isEnabled()))) {
            return invoker.invoke(invocation);
        }

        // 仅在 Provider 端入站时执行参数格式校验防卫
        if (!RpcContext.getContext().isConsumerSide()) {
            Object[] arguments = invocation.getArguments();
            if (arguments != null && arguments.length > 0) {
                for (Object arg : arguments) {
                    if (arg != null && !isSkippableParameterType(arg.getClass())) {
                        try {
                            Set<ConstraintViolation<Object>> violations = ValidatorUtil.validateResult(arg);
                            if (violations != null && !violations.isEmpty()) {
                                String errorMsg = DubboExceptionUtils.formatConstraintViolations(violations);
                                log.warn("[DUBBO-VALIDATION] Provider 入参校验拦截: service={}, method={}, error={}",
                                        invoker.getInterface().getName(), invocation.getMethodName(), errorMsg);
                                BusinessException businessException = new BusinessException(
                                        CommonResultCode.BAD_REQUEST.getCode(),
                                        errorMsg
                                );
                                return AsyncRpcResult.newDefaultAsyncResult(businessException, invocation);
                            }
                        } catch (Throwable t) {
                            log.debug("[DUBBO-VALIDATION] 执行参数校验异常: {}", t.getMessage());
                        }
                    }
                }
            }
        }

        return invoker.invoke(invocation);
    }

    private boolean isSkippableParameterType(Class<?> clazz) {
        return clazz.isPrimitive()
                || clazz.getName().startsWith("java.lang.")
                || clazz.getName().startsWith("java.util.")
                || DubboExceptionUtils.isStreamObserver(clazz);
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        var properties = DubboPropertiesHolder.get();
        if (properties != null && (!properties.isEnabled()
                || (properties.getValidation() != null && !properties.getValidation().isEnabled()))) {
            return;
        }

        if (!appResponse.hasException()) {
            return;
        }

        Throwable exception = appResponse.getException();
        ConstraintViolationException cve = DubboExceptionUtils.findConstraintViolationException(exception);
        if (cve != null) {
            String errorMessage = DubboExceptionUtils.formatConstraintViolations(cve.getConstraintViolations());
            log.warn("[DUBBO] JSR-303 参数校验拦截: service={}, method={}, error={}",
                    invoker.getInterface().getName(), invocation.getMethodName(), errorMessage);
            BusinessException businessException = new BusinessException(
                    CommonResultCode.BAD_REQUEST.getCode(),
                    errorMessage,
                    cve
            );
            appResponse.setException(businessException);
            appResponse.setValue(null);
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        // 异常由 onResponse 与基础异常过滤器统一调度
    }
}
