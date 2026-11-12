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
                if (e instanceof DecodeException && e.getCause() instanceof BusinessException be) {
                    throw be;
                }
                if (isTranslateNetworkExceptions()) {
                    throw FeignErrorSupport.translateFeignException(formatMethodKey(target, method), e);
                }
                throw e;
            }
        };
    }

    private boolean isTranslateNetworkExceptions() {
        return properties != null && properties.isTranslateNetworkExceptions();
    }

    private String formatMethodKey(Target target, Method method) {
        if (target != null && target.type() != null && method != null) {
            return Feign.configKey(target.type(), method);
        }
        if (method != null) {
            return method.getDeclaringClass().getSimpleName() + "#" + method.getName() + "()";
        }
        return "unknown";
    }
}
