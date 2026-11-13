package io.github.atengk.cloud.sentinel.fallback;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.endpoint.SentinelBlockCounter;
import io.github.atengk.cloud.sentinel.util.SentinelServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.core.ResolvableType;
import org.springframework.util.Assert;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

/**
 * 通用受管 Feign 降级工厂抽象基类
 * <p>
 * 基于 JDK 动态代理自动接管 OpenFeign 客户端的熔断降级生命周期：
 * <ul>
 *   <li><b>下游业务异常原样透传</b>：若原始异常已是非 {@link SentinelResultCode} 的普通 {@link BusinessException}，直接透传不覆写</li>
 *   <li><b>阻断异常精准映射</b>：若因果链包含 Sentinel {@link BlockException}（或既有 {@link SentinelResultCode} 异常），按规则精准映射为对应语义异常，其余网络/超时异常映射为 {@code DEGRADE (4202)} 并保留原始因果链</li>
 *   <li><b>默认方法放行与空安全兜底</b>：自动放行接口 {@code default} 默认方法，并对 {@code List}/{@code Collection}/{@code Iterable}/{@code Set}/{@code Map}/{@code Optional} 空降级返回值自动提供空容器兜底</li>
 *   <li><b>源头指标联动</b>：支持可选注入 {@link SentinelBlockCounter}，在降级源头精准累加阻断指标并标记防重</li>
 * </ul>
 *
 * @param <T> Feign 客户端接口类型
 * @author Ateng
 * @since 2026-10-29
 */
public abstract class AtengFallbackFactory<T> implements FallbackFactory<T> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final Class<T> targetType;

    private volatile SentinelBlockCounter blockCounter;

    /**
     * 默认构造器，通过子类泛型自动解析 Feign 客户端接口类型（向后兼容纯单测无参实例化）
     */
    @SuppressWarnings("unchecked")
    protected AtengFallbackFactory() {
        this.targetType = (Class<T>) ResolvableType.forClass(getClass())
                .as(AtengFallbackFactory.class)
                .getGeneric(0)
                .resolve();
        Assert.notNull(this.targetType, "Cannot resolve target Feign client interface for AtengFallbackFactory: " + getClass().getName());
        Assert.isTrue(this.targetType.isInterface(), "targetType must be an interface: " + targetType.getName());
    }

    /**
     * 通过泛型自动解析接口类型并显式注入阻断计数器
     *
     * @param blockCounter Sentinel 原子阻断计数器（可为 {@code null}）
     */
    protected AtengFallbackFactory(SentinelBlockCounter blockCounter) {
        this();
        this.blockCounter = blockCounter;
    }

    /**
     * 显式指定 Feign 客户端接口类型的构造器（兼容匿名子类或无法反射泛型场景）
     *
     * @param targetType Feign 客户端接口 Class 对象
     */
    protected AtengFallbackFactory(Class<T> targetType) {
        this(targetType, null);
    }

    /**
     * 显式指定 Feign 客户端接口类型并注入阻断计数器的构造器
     *
     * @param targetType   Feign 客户端接口 Class 对象
     * @param blockCounter Sentinel 原子阻断计数器（可为 {@code null}）
     */
    protected AtengFallbackFactory(Class<T> targetType, SentinelBlockCounter blockCounter) {
        Assert.notNull(targetType, "targetType must not be null");
        Assert.isTrue(targetType.isInterface(), "targetType must be an interface: " + targetType.getName());
        this.targetType = targetType;
        this.blockCounter = blockCounter;
    }

    /**
     * 获取当前受管降级工厂代理的 Feign 客户端接口类型
     *
     * @return Feign 客户端接口 Class 对象
     */
    public Class<T> getTargetType() {
        return targetType;
    }

    /**
     * Spring 可选装配阻断计数器，供源头阻断统计与切面防重使用
     *
     * @param blockCounter Sentinel 原子阻断计数器（可为 {@code null}）
     */
    @Autowired(required = false)
    public void setBlockCounter(SentinelBlockCounter blockCounter) {
        this.blockCounter = blockCounter;
    }

    /**
     * 获取当前关联的阻断计数器实例
     *
     * @return 阻断计数器，未注入时返回 {@code null}
     */
    protected SentinelBlockCounter getBlockCounter() {
        return blockCounter;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T create(Throwable cause) {
        return (T) Proxy.newProxyInstance(
                targetType.getClassLoader(),
                new Class<?>[]{targetType},
                new FallbackInvocationHandler(cause)
        );
    }

    /**
     * 核心降级分派模板方法，供业务子类按需覆写以实现柔性降级保底返回值。
     * <p>默认实现执行异常精准分流：
     * <ul>
     *   <li>若 {@code cause} 已是普通 {@link BusinessException}（非 {@link SentinelResultCode}），原样重新抛出</li>
     *   <li>若 {@code cause} 已是携带 {@link SentinelResultCode} 的 {@link BusinessException}，保留其原规则语义并同步源头计数防重标记</li>
     *   <li>若 {@code cause} 因果链包含 {@link BlockException}，精准映射为对应 {@link SentinelResultCode} 并保留因果链</li>
     *   <li>其余底层网络/超时异常，转译为携带原始 {@code cause} 的 {@link SentinelResultCode#DEGRADE} 异常</li>
     * </ul>
     *
     * @param method 被调用的 Feign 接口方法
     * @param args   方法参数数组
     * @param cause  触发降级的原始异常原因
     * @return 降级保底返回值，可返回 {@code null}（基本类型与集合/可选容器会自动兜底默认值）
     * @throws Throwable 业务感知的降级或下游透传异常
     */
    protected Object handleFallback(Method method, Object[] args, Throwable cause) throws Throwable {
        if (cause instanceof BusinessException businessEx) {
            SentinelResultCode existingCode = SentinelServletUtil.resolveSentinelResultCode(businessEx);
            if (existingCode == null) {
                log.debug("Feign client received downstream BusinessException, passing through untouched: client='{}', method='{}', code={}",
                        targetType.getSimpleName(), method.getName(), businessEx.getCode());
                throw businessEx;
            }
            if (businessEx instanceof SentinelBlockTracked || this.blockCounter == null) {
                throw businessEx;
            }
            // 未实现 SentinelBlockTracked 的上游 Sentinel 业务异常在源头已按原规则计数，补充追踪包装防止切面二次计数
            throw new TrackedFallbackBusinessException(existingCode, businessEx.getMessage(), businessEx);
        }

        BlockException blockEx = SentinelServletUtil.extractBlockException(cause);
        if (blockEx != null) {
            SentinelResultCode resultCode = SentinelServletUtil.determineResultCode(blockEx);
            String ruleType = SentinelServletUtil.determineRuleType(resultCode);
            log.warn("Feign client Sentinel block triggered: client='{}', method='{}', rule={}, code={}, cause='{}'",
                    targetType.getSimpleName(), method.getName(), ruleType, resultCode.getCode(), cause.toString());
            throw new TrackedFallbackBusinessException(
                    resultCode,
                    "Feign 远程调用触发 Sentinel 阻断 (" + ruleType + "): " + targetType.getSimpleName() + "#" + method.getName(),
                    cause);
        }

        log.warn("Feign client fallback triggered: client='{}', method='{}', cause='{}'",
                targetType.getSimpleName(), method.getName(),
                cause != null ? cause.toString() : "unknown");
        throw new TrackedFallbackBusinessException(
                SentinelResultCode.DEGRADE,
                "Feign 远程调用熔断降级: " + targetType.getSimpleName() + "#" + method.getName(),
                cause);
    }

    private boolean recordBlockAtSource(Method method, Throwable cause) {
        SentinelBlockCounter counter = this.blockCounter;
        if (counter == null) {
            return false;
        }
        String resource = targetType.getSimpleName() + "#" + method.getName();
        BlockException blockEx = resolveBlockForCounter(resource, cause);
        HttpServletRequest currentRequest = resolveCurrentRequest();
        String clientIp = currentRequest != null ? SentinelServletUtil.resolveClientIp(currentRequest) : "feign-client";
        String traceId = SentinelServletUtil.resolveTraceId();
        counter.recordBlock(blockEx, resource, clientIp, traceId, null);
        return true;
    }

    private static BlockException resolveBlockForCounter(String resource, Throwable cause) {
        BlockException blockEx = SentinelServletUtil.extractBlockException(cause);
        if (blockEx != null) {
            return blockEx;
        }
        if (cause instanceof BusinessException businessEx) {
            SentinelResultCode resultCode = SentinelServletUtil.resolveSentinelResultCode(businessEx);
            if (resultCode != null) {
                return SentinelServletUtil.createBlockExceptionForResultCode(
                        resultCode, resource, businessEx.getMessage());
            }
        }
        String message = cause != null ? cause.getMessage() : "Feign fallback degrade";
        return SentinelServletUtil.createBlockExceptionForResultCode(
                SentinelResultCode.DEGRADE, resource, message);
    }

    private static HttpServletRequest resolveCurrentRequest() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                return attributes.getRequest();
            }
        } catch (Throwable ignored) {
            // 非 Web 请求上下文或类路径无 Servlet 请求上下文时安全忽略
        }
        return null;
    }

    private static boolean shouldSkipSourceCount(Throwable cause) {
        if (cause instanceof SentinelBlockTracked tracked && tracked.isSourceCounted()) {
            return true;
        }
        return cause instanceof BusinessException businessEx
                && SentinelServletUtil.resolveSentinelResultCode(businessEx) == null;
    }

    private class FallbackInvocationHandler implements InvocationHandler {

        private final Throwable cause;

        FallbackInvocationHandler(Throwable cause) {
            this.cause = cause;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                String name = method.getName();
                if ("equals".equals(name)) {
                    return proxy == (args != null && args.length > 0 ? args[0] : null);
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(name)) {
                    return "AtengFallbackProxy<" + targetType.getName() + ">";
                }
                return method.invoke(this, args);
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }

            boolean countedAtSource = !shouldSkipSourceCount(cause) && recordBlockAtSource(method, cause);
            Object result;
            try {
                result = handleFallback(method, args, cause);
            } catch (TrackedFallbackBusinessException trackedEx) {
                trackedEx.markSourceCounted(countedAtSource);
                throw trackedEx;
            }
            if (result != null) {
                return result;
            }
            return resolveDefaultReturnValue(method.getReturnType());
        }
    }

    private static Object resolveDefaultReturnValue(Class<?> returnType) {
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        if (returnType.isPrimitive()) {
            return getDefaultPrimitiveValue(returnType);
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        if (Set.class.isAssignableFrom(returnType)) {
            if (returnType == NavigableSet.class || returnType == SortedSet.class) {
                return Collections.emptyNavigableSet();
            }
            if (returnType.isAssignableFrom(Collections.emptySet().getClass())) {
                return Collections.emptySet();
            }
        }
        if (List.class.isAssignableFrom(returnType) && returnType.isAssignableFrom(Collections.emptyList().getClass())) {
            return Collections.emptyList();
        }
        if (returnType == Collection.class || returnType == Iterable.class) {
            return Collections.emptyList();
        }
        if (Map.class.isAssignableFrom(returnType)) {
            if (returnType == NavigableMap.class || returnType == SortedMap.class) {
                return Collections.emptyNavigableMap();
            }
            if (returnType.isAssignableFrom(Collections.emptyMap().getClass())) {
                return Collections.emptyMap();
            }
        }
        return null;
    }

    private static Object getDefaultPrimitiveValue(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return Boolean.FALSE;
        }
        if (primitiveType == byte.class) {
            return (byte) 0;
        }
        if (primitiveType == short.class) {
            return (short) 0;
        }
        if (primitiveType == char.class) {
            return '\0';
        }
        if (primitiveType == int.class) {
            return 0;
        }
        if (primitiveType == long.class) {
            return 0L;
        }
        if (primitiveType == float.class) {
            return 0.0f;
        }
        if (primitiveType == double.class) {
            return 0.0d;
        }
        return null;
    }

    private static final class TrackedFallbackBusinessException extends BusinessException implements SentinelBlockTracked {

        private boolean sourceCounted;

        TrackedFallbackBusinessException(SentinelResultCode resultCode, String message, Throwable cause) {
            super(resultCode, message, cause);
        }

        void markSourceCounted(boolean sourceCounted) {
            this.sourceCounted = sourceCounted;
        }

        @Override
        public boolean isSourceCounted() {
            return sourceCounted;
        }
    }
}
