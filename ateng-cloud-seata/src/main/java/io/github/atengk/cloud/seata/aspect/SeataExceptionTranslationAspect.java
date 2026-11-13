package io.github.atengk.cloud.seata.aspect;

import io.github.atengk.cloud.seata.exception.SeataExceptionTranslator;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Seata 分布式事务异常因果转译与超时监测切面
 * <p>
 * 围绕标注 @GlobalTransactional 与 @GlobalLock 的核心业务方法，拦截并转译底层 Seata 驱动异常，
 * 消除原始 SDK 异常向业务上层与传输层泄漏的隐患，保障因果链 100% 完整；
 * 同时对 @GlobalTransactional 方法执行耗时进行阈值监测，超出 {@code ateng.cloud.seata.tx.default-timeout} 时输出结构化慢事务告警。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Aspect
public class SeataExceptionTranslationAspect implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(SeataExceptionTranslationAspect.class);

    private final SeataProperties seataProperties;

    /**
     * 切面执行顺序：优先于 Seata 默认拦截器捕获外部逃逸异常
     */
    private int order = Ordered.LOWEST_PRECEDENCE - 10;

    public SeataExceptionTranslationAspect() {
        this(null);
    }

    public SeataExceptionTranslationAspect(SeataProperties seataProperties) {
        this.seataProperties = seataProperties != null ? seataProperties : new SeataProperties();
    }

    @Around("@annotation(org.apache.seata.spring.annotation.GlobalTransactional) " +
            "|| @within(org.apache.seata.spring.annotation.GlobalTransactional) " +
            "|| @annotation(org.apache.seata.spring.annotation.GlobalLock) " +
            "|| @within(org.apache.seata.spring.annotation.GlobalLock)")
    public Object translateTransactionalException(ProceedingJoinPoint joinPoint) throws Throwable {
        boolean monitorTimeout = isGlobalTransactionalInvocation(joinPoint);
        long startNanos = monitorTimeout ? System.nanoTime() : 0L;
        String entryXid = monitorTimeout ? RootContext.getXID() : null;
        try {
            return joinPoint.proceed();
        } catch (Error error) {
            throw error;
        } catch (Throwable ex) {
            throw SeataExceptionTranslator.translate(ex);
        } finally {
            if (monitorTimeout) {
                logSlowTransactionIfExceeded(joinPoint, startNanos, entryXid);
            }
        }
    }

    private boolean isGlobalTransactionalInvocation(ProceedingJoinPoint joinPoint) {
        if (!(joinPoint.getSignature() instanceof MethodSignature methodSignature)) {
            return false;
        }
        Method method = methodSignature.getMethod();
        Class<?> targetClass = joinPoint.getTarget() != null
                ? joinPoint.getTarget().getClass()
                : method.getDeclaringClass();
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);
        if (AnnotatedElementUtils.hasAnnotation(specificMethod, GlobalTransactional.class)
                || AnnotatedElementUtils.hasAnnotation(method, GlobalTransactional.class)
                || AnnotatedElementUtils.hasAnnotation(targetClass, GlobalTransactional.class)
                || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), GlobalTransactional.class)) {
            return true;
        }
        for (Class<?> iface : ClassUtils.getAllInterfacesForClassAsSet(targetClass)) {
            Method ifaceMethod = ReflectionUtils.findMethod(iface, specificMethod.getName(), specificMethod.getParameterTypes());
            if ((ifaceMethod != null && AnnotatedElementUtils.hasAnnotation(ifaceMethod, GlobalTransactional.class))
                    || AnnotatedElementUtils.hasAnnotation(iface, GlobalTransactional.class)) {
                return true;
            }
        }
        return false;
    }

    private void logSlowTransactionIfExceeded(ProceedingJoinPoint joinPoint, long startNanos, String entryXid) {
        SeataProperties.TxProperties txProperties = seataProperties.getTx();
        Duration defaultTimeout = txProperties != null ? txProperties.getDefaultTimeout() : null;
        if (defaultTimeout == null || defaultTimeout.isZero() || defaultTimeout.isNegative()) {
            return;
        }
        long thresholdMillis = defaultTimeout.toMillis();
        if (thresholdMillis <= 0L) {
            return;
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        if (elapsedMillis > thresholdMillis) {
            String currentXid = RootContext.getXID();
            String effectiveXid = (currentXid != null && !currentXid.isBlank()) ? currentXid : entryXid;
            log.warn("Seata 全局事务方法执行耗时超过默认超时阈值: method={}, xid={}, elapsedMs={}ms, thresholdMs={}ms",
                    joinPoint.getSignature().toShortString(),
                    effectiveXid != null ? effectiveXid : "NONE",
                    elapsedMillis,
                    thresholdMillis);
        }
    }

    public SeataProperties getSeataProperties() {
        return seataProperties;
    }

    @Override
    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }
}
