package io.github.atengk.cloud.sentinel.fallback;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.core.ResolvableType;
import org.springframework.util.Assert;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 通用受管 Feign 降级工厂抽象基类
 * <p>
 * 基于 JDK 动态代理自动接管 OpenFeign 客户端的熔断降级生命周期：
 * <ul>
 *   <li><b>默认行为</b>：统一将底层异常（BlockException、DegradeException、SocketTimeoutException 等）转译为携带原始因果链的语义化 {@link BusinessException} (错误码 {@code SentinelResultCode.DEGRADE})</li>
 *   <li><b>柔性扩展</b>：业务子类可通过重写 {@link #handleFallback(Method, Object[], Throwable)} 返回特定业务保底 POJO 或空集合</li>
 * </ul>
 *
 * @param <T> Feign 客户端接口类型
 * @author Ateng
 * @since 2026-10-29
 */
public abstract class AtengFallbackFactory<T> implements FallbackFactory<T> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final Class<T> targetType;

    /**
     * 默认构造函数：自动通过泛型反射解析目标 Feign 接口类型
     */
    @SuppressWarnings("unchecked")
    protected AtengFallbackFactory() {
        this.targetType = (Class<T>) ResolvableType.forClass(getClass())
                .as(AtengFallbackFactory.class)
                .getGeneric(0)
                .resolve();
        Assert.notNull(this.targetType, "Cannot resolve target Feign client interface for AtengFallbackFactory: " + getClass().getName());
        Assert.isTrue(this.targetType.isInterface(), "targetType must be an interface: " + this.targetType.getName());
    }

    /**
     * 显式指定目标 Feign 接口类型的构造函数
     *
     * @param targetType 目标接口 Class
     */
    protected AtengFallbackFactory(Class<T> targetType) {
        Assert.notNull(targetType, "targetType must not be null");
        Assert.isTrue(targetType.isInterface(), "targetType must be an interface: " + targetType.getName());
        this.targetType = targetType;
    }

    /**
     * 获取受管的 Feign 客户端接口类型
     *
     * @return 接口 Class
     */
    public Class<T> getTargetType() {
        return targetType;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T create(Throwable cause) {
        ClassLoader classLoader = targetType.getClassLoader() != null
                ? targetType.getClassLoader()
                : Thread.currentThread().getContextClassLoader();

        return (T) Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{targetType},
                new FallbackInvocationHandler(cause)
        );
    }

    /**
     * 降级处理扩展钩子
     * <p>
     * 默认抛出携带原始 cause 的 {@link BusinessException}。
     * 业务子类可按需重写此方法返回特定保底 POJO 或 Mock 数据。
     *
     * @param method 被调用的接口方法
     * @param args   调用实参
     * @param cause  触发降级的底层异常
     * @return 降级返回值 (须与方法返回类型兼容)
     * @throws Throwable 降级异常
     */
    protected Object handleFallback(Method method, Object[] args, Throwable cause) throws Throwable {
        log.warn("Feign client fallback triggered: client='{}', method='{}', cause='{}'",
                targetType.getSimpleName(), method.getName(),
                cause != null ? cause.toString() : "unknown");
        throw new BusinessException(SentinelResultCode.DEGRADE, "Feign 远程调用熔断降级: " + targetType.getSimpleName() + "#" + method.getName(), cause);
    }

    private class FallbackInvocationHandler implements InvocationHandler {

        private final Throwable cause;

        FallbackInvocationHandler(Throwable cause) {
            this.cause = cause;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 1. 防御 Object 基础方法，避免意外触发降级或递归死锁
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "AtengFallbackProxy<" + targetType.getName() + ">";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> method.invoke(this, args);
                };
            }

            // 2. 执行受保护的降级扩展钩子
            Object result = handleFallback(method, args, cause);

            // 3. 基本类型与 void 返回防御
            Class<?> returnType = method.getReturnType();
            if (returnType == void.class) {
                return null;
            }
            if (returnType.isPrimitive() && result == null) {
                return getDefaultPrimitiveValue(returnType);
            }

            return result;
        }

        private Object getDefaultPrimitiveValue(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0.0f;
            if (type == double.class) return 0.0d;
            if (type == char.class) return '\0';
            return null;
        }
    }
}
