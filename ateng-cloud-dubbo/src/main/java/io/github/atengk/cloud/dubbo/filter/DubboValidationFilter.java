package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
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
import java.util.stream.Collectors;

/**
 * Dubbo JSR-303 参数校验防卫与结构化转译过滤器
 * <p>
 * 1. 在 Provider 入口处对 RPC 入参执行 JSR-303 格式校验 (Fail-Fast 防卫)；
 * 2. 拦截并转译 {@link ConstraintViolationException}，提取具体违规字段路径与错误描述，
 *    统一结构化转译为框架标准受管 {@link BusinessException} (BAD_REQUEST / 400)。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate(group = {CommonConstants.PROVIDER, CommonConstants.CONSUMER}, order = -9000)
public class DubboValidationFilter implements Filter, BaseFilter.Listener {

    private static final Logger log = LoggerFactory.getLogger(DubboValidationFilter.class);

    private static volatile Validator cachedValidator;

    private Validator getValidator() {
        if (cachedValidator == null) {
            synchronized (DubboValidationFilter.class) {
                if (cachedValidator == null) {
                    try {
                        cachedValidator = Validation.buildDefaultValidatorFactory().getValidator();
                    } catch (Throwable t) {
                        log.debug("[DUBBO-VALIDATION] 初始化 Validator 提示: {}", t.getMessage());
                    }
                }
            }
        }
        return cachedValidator;
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        var properties = DubboPropertiesHolder.get();
        if (properties != null && properties.getValidation() != null && !properties.getValidation().isEnabled()) {
            return invoker.invoke(invocation);
        }

        // 仅在 Provider 端入站时执行参数格式校验防卫
        if (!RpcContext.getContext().isConsumerSide()) {
            Object[] arguments = invocation.getArguments();
            Validator validator = getValidator();
            if (validator != null && arguments != null && arguments.length > 0) {
                for (Object arg : arguments) {
                    if (arg != null && !isPrimitiveOrWrapper(arg.getClass())) {
                        try {
                            Set<ConstraintViolation<Object>> violations = validator.validate(arg);
                            if (violations != null && !violations.isEmpty()) {
                                String errorMsg = formatViolations(violations);
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

    private boolean isPrimitiveOrWrapper(Class<?> clazz) {
        return clazz.isPrimitive()
                || clazz.getName().startsWith("java.lang.")
                || clazz.getName().startsWith("java.util.")
                || org.apache.dubbo.common.stream.StreamObserver.class.isAssignableFrom(clazz)
                || clazz.getName().contains("StreamObserver");
    }

    private String formatViolations(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(v -> {
                    String path = v.getPropertyPath() != null ? v.getPropertyPath().toString() : "";
                    int dotIdx = path.lastIndexOf('.');
                    String field = dotIdx >= 0 ? path.substring(dotIdx + 1) : path;
                    return field + " " + v.getMessage();
                })
                .collect(Collectors.joining("; ", "参数校验失败: ", ""));
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        var properties = DubboPropertiesHolder.get();
        if (properties != null && properties.getValidation() != null && !properties.getValidation().isEnabled()) {
            return;
        }

        if (!appResponse.hasException()) {
            return;
        }

        Throwable exception = appResponse.getException();
        ConstraintViolationException cve = findConstraintViolationException(exception);
        if (cve != null) {
            String errorMessage = extractValidationErrorMessage(cve);
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

    private ConstraintViolationException findConstraintViolationException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException cve) {
                return cve;
            }
            if ("jakarta.validation.ConstraintViolationException".equals(current.getClass().getName())
                    || "javax.validation.ConstraintViolationException".equals(current.getClass().getName())) {
                try {
                    if (current instanceof ConstraintViolationException cve) {
                        return cve;
                    }
                } catch (Exception ignored) {
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private String extractValidationErrorMessage(ConstraintViolationException cve) {
        Set<ConstraintViolation<?>> violations = cve.getConstraintViolations();
        if (violations == null || violations.isEmpty()) {
            return cve.getMessage() != null ? cve.getMessage() : "参数校验失败";
        }
        return formatViolations(violations);
    }
}
