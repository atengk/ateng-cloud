package io.github.atengk.cloud.sentinel.advice;

import com.alibaba.csp.sentinel.Tracer;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import io.github.atengk.cloud.sentinel.util.SentinelServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.ClassUtils;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Web 异常 Sentinel 熔断追踪自动桥接器
 *
 * <p>在全局 {@code @RestControllerAdvice}（如 {@code GlobalExceptionHandler}）消化未受检异常之前，
 * 自动对非豁免的系统故障异常执行单次请求幂等的 {@link Tracer#trace(Throwable)}，
 * 根治 Web 接口异常比例与异常数熔断器因全局异常切面吞噬异常而静默失效的问题。</p>
 *
 * <p>默认豁免以下不代表服务端系统故障的异常类型：
 * <ul>
 *     <li>Sentinel 自身抛出的 {@code BlockException} 及其反射/代理包装异常；</li>
 *     <li>Spring MVC 客户端 4xx 参数校验与协议异常（实现 {@link ErrorResponse} 且为 4xx、
 *         {@link BindException}、{@link HttpMessageNotReadableException}、
 *         {@link MethodArgumentTypeMismatchException}、Jakarta {@code ValidationException}
 *         或标注 4xx {@link ResponseStatus} 的异常）；</li>
 *     <li>常规业务异常 {@link BusinessException}（除非显式开启 {@code traceBusinessException=true}，
 *         且携带 {@code SentinelResultCode} 的阻断降级异常始终豁免以免二次放大熔断）；</li>
 *     <li>用户通过 {@code ateng.cloud.sentinel.trace.ignore-exceptions} 自定义声明的异常类。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-11-13
 */
public class SentinelExceptionTraceAdvice implements HandlerExceptionResolver, Ordered {

    /**
     * 用于在当前 {@link HttpServletRequest} 中标记异常已被追踪过的请求属性键（保障单次请求幂等）
     */
    public static final String TRACE_RECORDED_ATTRIBUTE = SentinelExceptionTraceAdvice.class.getName() + ".TRACE_RECORDED";

    private static final Logger log = LoggerFactory.getLogger(SentinelExceptionTraceAdvice.class);

    private static final Class<?> JAKARTA_VALIDATION_EXCEPTION_CLASS = resolveOptionalClass(
            "jakarta.validation.ValidationException");

    private final AtengSentinelProperties properties;

    /**
     * 构造 Web 异常 Sentinel 熔断追踪桥接器
     *
     * @param properties Sentinel 配置属性
     */
    public SentinelExceptionTraceAdvice(AtengSentinelProperties properties) {
        this.properties = properties != null ? properties : new AtengSentinelProperties();
    }

    /**
     * 高优先级运行于 {@code ExceptionHandlerExceptionResolver}(order=0) 及
     * {@link SentinelBlockExceptionAdvice} 之前，仅负责向当前活跃 Sentinel 上下文桥接异常计数，
     * 恒返回 {@code null} 以无缝放行至后续全局异常响应渲染。
     *
     * @return 优先级值 {@code Ordered.HIGHEST_PRECEDENCE + 50}
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        AtengSentinelProperties.TraceProperties traceProps = properties.getTrace();
        if (!traceProps.isEnabled() || ex == null) {
            return null;
        }
        if (request != null && Boolean.TRUE.equals(request.getAttribute(TRACE_RECORDED_ATTRIBUTE))) {
            return null;
        }
        if (shouldExempt(ex, traceProps)) {
            return null;
        }
        if (request != null) {
            request.setAttribute(TRACE_RECORDED_ATTRIBUTE, Boolean.TRUE);
        }
        Tracer.trace(ex);
        return null;
    }

    private boolean shouldExempt(Throwable ex, AtengSentinelProperties.TraceProperties traceProps) {
        // 1. Sentinel 自身阻断异常（含代理/反射包装）绝不计入业务系统故障率
        if (SentinelServletUtil.extractBlockException(ex) != null) {
            return true;
        }

        Throwable unwrapped = unwrapReflectionWrapper(ex);

        // 2. 业务异常按配置决定是否计入，其中 Sentinel 自身阻断降级错误码始终豁免
        if (unwrapped instanceof BusinessException businessEx) {
            if (SentinelServletUtil.resolveSentinelResultCode(businessEx) != null) {
                return true;
            }
            if (!traceProps.isTraceBusinessException()) {
                return true;
            }
        }

        // 3. 客户端 4xx 参数校验或协议错误不应触发服务端熔断
        if (isClient4xxException(ex) || (unwrapped != ex && isClient4xxException(unwrapped))) {
            return true;
        }

        // 4. 用户自定义豁免异常列表
        List<Class<? extends Throwable>> ignoreExceptions = traceProps.getIgnoreExceptions();
        if (ignoreExceptions != null && !ignoreExceptions.isEmpty()) {
            for (Class<? extends Throwable> ignoreType : ignoreExceptions) {
                if (ignoreType != null && (ignoreType.isInstance(ex) || ignoreType.isInstance(unwrapped))) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isClient4xxException(Throwable ex) {
        if (ex == null) {
            return false;
        }
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            return true;
        }
        if (ex instanceof BindException
                || ex instanceof HttpMessageNotReadableException
                || ex instanceof MethodArgumentTypeMismatchException) {
            return true;
        }
        if (JAKARTA_VALIDATION_EXCEPTION_CLASS != null && JAKARTA_VALIDATION_EXCEPTION_CLASS.isInstance(ex)) {
            return true;
        }
        ResponseStatus responseStatus = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
        return responseStatus != null && responseStatus.code().is4xxClientError();
    }

    private static Throwable unwrapReflectionWrapper(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            if (current instanceof UndeclaredThrowableException ute && ute.getUndeclaredThrowable() != null) {
                current = ute.getUndeclaredThrowable();
                continue;
            }
            if (current instanceof InvocationTargetException ite && ite.getTargetException() != null) {
                current = ite.getTargetException();
                continue;
            }
            break;
        }
        return current;
    }

    private static Class<?> resolveOptionalClass(String className) {
        try {
            return ClassUtils.forName(className, SentinelExceptionTraceAdvice.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ex) {
            log.debug("Optional exception class '{}' not present on classpath, exemption disabled: {}", className, ex.getMessage());
            return null;
        }
    }
}
