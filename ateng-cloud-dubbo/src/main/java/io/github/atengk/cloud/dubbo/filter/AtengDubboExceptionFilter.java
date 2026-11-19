package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.overload.DubboOverloadListener;
import io.github.atengk.cloud.dubbo.properties.AtengDubboExceptionProperties;
import io.github.atengk.cloud.dubbo.support.DubboAttachmentSupport;
import io.github.atengk.cloud.dubbo.support.DubboExceptionUtils;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import jakarta.validation.ConstraintViolationException;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.BaseFilter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.RpcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.List;

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
        AtengDubboExceptionProperties exceptionProps = properties != null ? properties.getException() : null;
        if (properties != null && (!properties.isEnabled()
                || (exceptionProps != null && !exceptionProps.isEnabled()))) {
            return;
        }

        if (!appResponse.hasException() || GenericService.class == invoker.getInterface()) {
            return;
        }

        Throwable exception = appResponse.getException();
        String traceId = resolveProviderTraceId(invocation);

        // 1. 受管 BusinessException：按开关校验 cause/suppressed 链并安全剥离不可信 cause，同步补全 traceId
        if (exception instanceof BusinessException be) {
            String effectiveTraceId = StringUtils.hasText(be.getTraceId()) ? be.getTraceId() : traceId;
            boolean sanitize = exceptionProps == null || exceptionProps.isSanitizeUntrustedCause();
            List<String> trustedPackages = exceptionProps != null ? exceptionProps.getTrustedCausePackages() : null;
            Throwable untrustedCause = sanitize ? DubboExceptionUtils.findFirstUntrustedCause(be, trustedPackages) : null;
            if (untrustedCause != null) {
                log.warn("[DUBBO] Provider BusinessException 携带非白名单 cause，已在出口安全剥离: service={}, method={}, traceId={}, causeClass={}, errorMsg={}",
                        invoker.getInterface().getName(), RpcUtils.getMethodName(invocation), effectiveTraceId,
                        untrustedCause.getClass().getName(), be.getMsg(), be);
                String baseMsg = StringUtils.hasText(be.getMsg()) ? be.getMsg() : be.getMessage();
                String sanitizedMsg = formatSanitizedCauseMessage(
                        StringUtils.hasText(baseMsg) ? baseMsg : "业务执行异常", untrustedCause);
                BusinessException sanitized = be.getResultCode() != null
                        ? new BusinessException(be.getResultCode(), sanitizedMsg)
                        : new BusinessException(
                                be.getCode() != null ? be.getCode() : CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                                sanitizedMsg);
                sanitized.withTraceId(effectiveTraceId);
                appResponse.setException(sanitized);
                return;
            }
            if (!StringUtils.hasText(be.getTraceId()) && StringUtils.hasText(traceId)) {
                be.withTraceId(traceId);
            }
            return;
        }

        // 2. JSR-303 参数校验异常：结构化转译为标准受管 BusinessException(BAD_REQUEST)
        ConstraintViolationException cve = DubboExceptionUtils.findConstraintViolationException(exception);
        if (cve != null) {
            String errorMsg = DubboExceptionUtils.formatConstraintViolations(cve.getConstraintViolations());
            log.warn("[DUBBO] Provider 拦截 JSR-303 参数校验失败: service={}, method={}, traceId={}, error={}",
                    invoker.getInterface().getName(), RpcUtils.getMethodName(invocation), traceId, errorMsg, cve);
            appResponse.setException(buildTranslatedBusinessException(
                    CommonResultCode.BAD_REQUEST.getCode(), errorMsg, cve, exceptionProps, traceId));
            return;
        }

        // 3. 服务端线程池过载拒绝异常：转译为受管 BusinessException(TOO_MANY_REQUESTS) 并记录过载诊断
        if (DubboExceptionUtils.isOverloadException(exception)) {
            DubboOverloadListener.incrementRejectCount();
            log.warn("[DUBBO-OVERLOAD] Provider 拦截线程池耗尽过载异常: service={}, method={}, traceId={}, error={}",
                    invoker.getInterface().getName(), RpcUtils.getMethodName(invocation), traceId, exception.getMessage(), exception);
            appResponse.setException(buildTranslatedBusinessException(
                    CommonResultCode.TOO_MANY_REQUESTS.getCode(),
                    "服务端并发线程池已耗尽，触发过载保护限流",
                    exception,
                    exceptionProps,
                    traceId));
            return;
        }

        // 4. 受检异常 (Checked Exception，非 RuntimeException 且属于 Exception)：若因果链安全则直接放行
        if (!(exception instanceof RuntimeException) && (exception instanceof Exception)) {
            sanitizeWhitelistedIfUntrusted(exception, false, invoker, invocation, appResponse, exceptionProps, traceId);
            return;
        }

        // 5. 方法签名 throws 中已显式声明的异常类型：仅校检嵌套 cause/suppressed 安全后放行
        try {
            Method method = invoker.getInterface().getMethod(RpcUtils.getMethodName(invocation), invocation.getParameterTypes());
            Class<?>[] exceptionClasses = method.getExceptionTypes();
            for (Class<?> exceptionClass : exceptionClasses) {
                if (exceptionClass.isAssignableFrom(exception.getClass())) {
                    sanitizeWhitelistedIfUntrusted(exception, true, invoker, invocation, appResponse, exceptionProps, traceId);
                    return;
                }
            }
        } catch (NoSuchMethodException e) {
            log.debug("[DUBBO] Provider 未能在接口中定位目标方法签名: method={}, error={}",
                    RpcUtils.getMethodName(invocation), e.getMessage());
        }

        // 6. JDK 标准核心异常 (java.*, javax.*, jakarta.*)：若因果链安全则直接放行
        String className = exception.getClass().getName();
        if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jakarta.")) {
            sanitizeWhitelistedIfUntrusted(exception, false, invoker, invocation, appResponse, exceptionProps, traceId);
            return;
        }

        // 7. Dubbo 原生受管 RpcException：仅校验嵌套 cause/suppressed 安全后放行
        if (exception instanceof RpcException) {
            sanitizeWhitelistedIfUntrusted(exception, true, invoker, invocation, appResponse, exceptionProps, traceId);
            return;
        }

        // 8. 未受检的第三方底层私有异常：服务端完整保留日志现场，按开关安全转译为受管 BusinessException
        translateUntrustedException(exception, invoker, invocation, appResponse, exceptionProps, traceId);
    }

    private void sanitizeWhitelistedIfUntrusted(Throwable exception,
                                                boolean nestedOnly,
                                                Invoker<?> invoker,
                                                Invocation invocation,
                                                Result appResponse,
                                                AtengDubboExceptionProperties exceptionProps,
                                                String traceId) {
        boolean sanitize = exceptionProps == null || exceptionProps.isSanitizeUntrustedCause();
        List<String> trustedPackages = exceptionProps != null ? exceptionProps.getTrustedCausePackages() : null;
        Throwable untrusted = null;
        if (sanitize) {
            untrusted = nestedOnly
                    ? DubboExceptionUtils.findUntrustedNestedCause(exception, trustedPackages)
                    : DubboExceptionUtils.findFirstUntrustedCause(exception, trustedPackages);
        }
        if (untrusted != null) {
            translateUntrustedException(exception, untrusted, invoker, invocation, appResponse, traceId);
        }
    }

    private void translateUntrustedException(Throwable exception,
                                             Invoker<?> invoker,
                                             Invocation invocation,
                                             Result appResponse,
                                             AtengDubboExceptionProperties exceptionProps,
                                             String traceId) {
        boolean sanitize = exceptionProps == null || exceptionProps.isSanitizeUntrustedCause();
        List<String> trustedPackages = exceptionProps != null ? exceptionProps.getTrustedCausePackages() : null;
        Throwable untrustedCause = sanitize
                ? DubboExceptionUtils.findFirstUntrustedCause(exception, trustedPackages)
                : null;
        if (untrustedCause == null) {
            log.warn("[DUBBO] Provider 未声明未受检异常转译: service={}, method={}, traceId={}, errorClass={}, errorMsg={}",
                    invoker.getInterface().getName(), RpcUtils.getMethodName(invocation), traceId,
                    exception.getClass().getName(), exception.getMessage(), exception);
            String errorMsg = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
            appResponse.setException(new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "RPC 底层调用未知异常: " + errorMsg,
                    exception).withTraceId(traceId));
            return;
        }
        translateUntrustedException(exception, untrustedCause, invoker, invocation, appResponse, traceId);
    }

    private void translateUntrustedException(Throwable exception,
                                             Throwable untrustedCause,
                                             Invoker<?> invoker,
                                             Invocation invocation,
                                             Result appResponse,
                                             String traceId) {
        log.warn("[DUBBO] Provider 未声明或夹带非白名单 cause 异常转译: service={}, method={}, traceId={}, errorClass={}, untrustedCauseClass={}, errorMsg={}",
                invoker.getInterface().getName(), RpcUtils.getMethodName(invocation), traceId,
                exception.getClass().getName(), untrustedCause.getClass().getName(), exception.getMessage(), exception);
        String errorMsg = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
        String sanitizedMsg = formatSanitizedCauseMessage("RPC 底层调用未知异常: " + errorMsg, untrustedCause);
        appResponse.setException(new BusinessException(
                CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), sanitizedMsg).withTraceId(traceId));
    }

    private BusinessException buildTranslatedBusinessException(int code,
                                                               String baseMessage,
                                                               Throwable originalException,
                                                               AtengDubboExceptionProperties exceptionProps,
                                                               String traceId) {
        boolean sanitize = exceptionProps == null || exceptionProps.isSanitizeUntrustedCause();
        List<String> trustedPackages = exceptionProps != null ? exceptionProps.getTrustedCausePackages() : null;
        Throwable untrustedCause = sanitize
                ? DubboExceptionUtils.findFirstUntrustedCause(originalException, trustedPackages)
                : null;
        if (untrustedCause == null) {
            return new BusinessException(code, baseMessage, originalException).withTraceId(traceId);
        }
        String sanitizedMsg = formatSanitizedCauseMessage(baseMessage, untrustedCause);
        return new BusinessException(code, sanitizedMsg).withTraceId(traceId);
    }

    private static String formatSanitizedCauseMessage(String baseMessage, Throwable untrustedCause) {
        String simpleName = untrustedCause != null ? untrustedCause.getClass().getSimpleName() : "Unknown";
        return (baseMessage != null && baseMessage.contains(simpleName))
                ? baseMessage
                : baseMessage + " [cause=" + simpleName + "]";
    }

    private String resolveProviderTraceId(Invocation invocation) {
        return DubboAttachmentSupport.resolveTraceIdOrNull(invocation, false);
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        log.error("[DUBBO] Provider 执行发生系统调用错误: service={}, method={}, error={}",
                invoker.getInterface().getName(), RpcUtils.getMethodName(invocation), t.getMessage(), t);
    }
}
