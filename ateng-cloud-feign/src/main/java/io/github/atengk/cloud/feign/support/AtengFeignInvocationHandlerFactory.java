package io.github.atengk.cloud.feign.support;

import feign.Feign;
import feign.FeignException;
import feign.InvocationHandlerFactory;
import feign.Target;
import feign.codec.DecodeException;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Feign 动态代理调用异常治理工厂
 * <p>
 * 1. 始终剥离 {@link DecodeException} 内部承载的 {@link BusinessException} 直接向外抛出；
 * 2. 当开启 {@code ateng.cloud.feign.translate-network-exceptions=true} 时，
 * 将穿透重试器的 {@link feign.RetryableException} 与未转译 {@link FeignException}
 * 因果转译为携带原始异常作为 {@code cause} 的受管 {@link BusinessException}；
 * 默认保持原生 {@code RetryableException} 抛出以兼容下游熔断器统计。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-12
 */
public class AtengFeignInvocationHandlerFactory implements InvocationHandlerFactory {

    private final InvocationHandlerFactory delegate = new InvocationHandlerFactory.Default();
    private final AtengFeignProperties properties;

    public AtengFeignInvocationHandlerFactory(AtengFeignProperties properties) {
        this.properties = properties;
    }

    @Override
    public InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch) {
        InvocationHandler defaultHandler = delegate.create(target, dispatch);
        return (proxy, method, args) -> {
            try {
                return defaultHandler.invoke(proxy, method, args);
            } catch (FeignException e) {
                throw translateInvocationException(target != null ? target.type() : null, method, e, properties);
            }
        };
    }

    /**
     * 统一执行 Feign 代理调用异常剥壳（{@link DecodeException} 还原为 {@link BusinessException}）
     * 与可选网络异常因果转译，供原生代理工厂与 SentinelFeign 兼容代理共用
     *
     * @param targetType Feign 接口类型（可为 {@code null}）
     * @param method     被调用方法
     * @param exception  捕获的 {@link FeignException}
     * @param properties Feign 配置属性
     * @return 处理后的受管异常或原异常
     */
    public static RuntimeException translateInvocationException(Class<?> targetType,
                                                               Method method,
                                                               FeignException exception,
                                                               AtengFeignProperties properties) {
        if (exception instanceof DecodeException && exception.getCause() instanceof BusinessException be) {
            return be;
        }
        if (properties != null && properties.isTranslateNetworkExceptions()) {
            return FeignErrorSupport.translateFeignException(formatMethodKey(targetType, method), exception);
        }
        return exception;
    }

    private static String formatMethodKey(Class<?> targetType, Method method) {
        if (targetType != null && method != null) {
            return Feign.configKey(targetType, method);
        }
        if (method != null) {
            return method.getDeclaringClass().getSimpleName() + "#" + method.getName() + "()";
        }
        return "unknown";
    }
}
