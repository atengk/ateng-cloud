package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.cloud.dubbo.support.DubboAttachmentSupport;
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
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.temporal.TemporalAccessor;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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

    private static final int MAX_VALIDATION_DEPTH = 16;

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        var properties = DubboPropertiesHolder.get();
        if (properties != null && (!properties.isEnabled()
                || (properties.getValidation() != null && !properties.getValidation().isEnabled()))) {
            return invoker.invoke(invocation);
        }

        // 仅在 Provider 端入站时执行参数格式校验防卫（含批量集合、Map 值与对象数组级联遍历）
        if (!DubboAttachmentSupport.isConsumerSide(invoker)) {
            Object[] arguments = invocation != null ? invocation.getArguments() : null;
            if (arguments != null && arguments.length > 0) {
                for (Object arg : arguments) {
                    String errorMsg = validateArgumentTree(arg, "", 0, null);
                    if (errorMsg != null) {
                        String serviceName = invoker != null && invoker.getInterface() != null
                                ? invoker.getInterface().getName()
                                : "unknown";
                        String methodName = invocation.getMethodName();
                        log.warn("[DUBBO-VALIDATION] Provider 入参校验拦截: service={}, method={}, error={}",
                                serviceName, methodName, errorMsg);
                        BusinessException businessException = new BusinessException(
                                CommonResultCode.BAD_REQUEST.getCode(),
                                errorMsg
                        );
                        return AsyncRpcResult.newDefaultAsyncResult(businessException, invocation);
                    }
                }
            }
        }

        return invoker.invoke(invocation);
    }

    private String validateArgumentTree(Object target, String pathPrefix, int depth, Set<Object> visited) {
        if (target == null || depth >= MAX_VALIDATION_DEPTH) {
            return null;
        }
        Class<?> clazz = target.getClass();
        if (DubboExceptionUtils.isStreamObserver(clazz)) {
            return null;
        }

        if (target instanceof Iterable<?> iterable) {
            return visitContainerWithCycleGuard(target, visited, activeVisited -> {
                int index = 0;
                for (Object element : iterable) {
                    String failure = validateArgumentTree(element, pathPrefix + "[" + index + "]", depth + 1, activeVisited);
                    if (failure != null) {
                        return failure;
                    }
                    index++;
                }
                return null;
            });
        }

        if (target instanceof Map<?, ?> map) {
            return visitContainerWithCycleGuard(target, visited, activeVisited -> {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String keyLabel = entry.getKey() != null ? String.valueOf(entry.getKey()) : "null";
                    String failure = validateArgumentTree(entry.getValue(), pathPrefix + "[" + keyLabel + "]", depth + 1, activeVisited);
                    if (failure != null) {
                        return failure;
                    }
                }
                return null;
            });
        }

        if (clazz.isArray()) {
            if (clazz.getComponentType().isPrimitive()) {
                return null;
            }
            Object[] array = (Object[]) target;
            return visitContainerWithCycleGuard(target, visited, activeVisited -> {
                for (int i = 0; i < array.length; i++) {
                    String failure = validateArgumentTree(array[i], pathPrefix + "[" + i + "]", depth + 1, activeVisited);
                    if (failure != null) {
                        return failure;
                    }
                }
                return null;
            });
        }

        if (isSkippableScalarType(clazz)) {
            return null;
        }
        return validateSingleBean(target, pathPrefix);
    }

    private String visitContainerWithCycleGuard(Object container, Set<Object> visited, Function<Set<Object>, String> walker) {
        Set<Object> activeVisited = visited != null ? visited : Collections.newSetFromMap(new IdentityHashMap<>());
        if (!activeVisited.add(container)) {
            return null;
        }
        try {
            return walker.apply(activeVisited);
        } finally {
            activeVisited.remove(container);
        }
    }

    private String validateSingleBean(Object target, String pathPrefix) {
        try {
            Set<ConstraintViolation<Object>> violations = ValidatorUtil.validateResult(target);
            if (violations != null && !violations.isEmpty()) {
                return DubboExceptionUtils.formatConstraintViolations(violations, pathPrefix);
            }
        } catch (Throwable t) {
            log.debug("[DUBBO-VALIDATION] 执行参数校验异常: {}", t.getMessage());
        }
        return null;
    }

    private boolean isSkippableScalarType(Class<?> clazz) {
        return clazz.isPrimitive()
                || clazz.isEnum()
                || CharSequence.class.isAssignableFrom(clazz)
                || Number.class.isAssignableFrom(clazz)
                || Boolean.class == clazz
                || Character.class == clazz
                || TemporalAccessor.class.isAssignableFrom(clazz)
                || Date.class.isAssignableFrom(clazz)
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
