package io.github.atengk.cloud.seata.aspect;

import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.apache.seata.core.context.GlobalLockConfigHolder;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.model.GlobalLockConfig;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

/**
 * 高并发轻量防脏读 @GlobalLock 声明式拦截切面
 * <p>
 * 拦截标注有 @GlobalLock 注解的方法或类，在方法执行前绑定全局锁标记 {@link RootContext#bindGlobalLockFlag()}，
 * 并结合生产配置 {@link SeataProperties.LockProperties} 装配 {@link GlobalLockConfigHolder}。
 * 在方法执行完毕后（finally 块中）严格执行确定性解绑与上下文清理，杜绝锁标记泄露。
 * 配合 SELECT ... FOR UPDATE 仅向 TC 校验全局排他锁，免去 undo_log 开销。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class SeataGlobalLockAspect {

    private static final Logger log = LoggerFactory.getLogger(SeataGlobalLockAspect.class);

    private final SeataProperties seataProperties;

    public SeataGlobalLockAspect(SeataProperties seataProperties) {
        this.seataProperties = seataProperties != null ? seataProperties : new SeataProperties();
    }

    @Around("@annotation(org.apache.seata.spring.annotation.GlobalLock) || " +
            "@annotation(io.seata.spring.annotation.GlobalLock) || " +
            "@within(org.apache.seata.spring.annotation.GlobalLock) || " +
            "@within(io.seata.spring.annotation.GlobalLock)")
    public Object handleGlobalLock(ProceedingJoinPoint joinPoint) throws Throwable {
        boolean alreadyRequireGlobalLock = RootContext.requireGlobalLock();
        if (!alreadyRequireGlobalLock) {
            RootContext.bindGlobalLockFlag();
            log.debug("绑定 Seata GlobalLock 标记至 RootContext (线程: [{}])", Thread.currentThread().getName());
        }

        GlobalLockConfig config = resolveGlobalLockConfig(joinPoint);
        GlobalLockConfig previousConfig = GlobalLockConfigHolder.setAndReturnPrevious(config);

        try {
            return joinPoint.proceed();
        } finally {
            if (!alreadyRequireGlobalLock) {
                RootContext.unbindGlobalLockFlag();
                log.debug("解绑 Seata GlobalLock 标记 (线程: [{}])", Thread.currentThread().getName());
            }
            if (previousConfig != null) {
                GlobalLockConfigHolder.setAndReturnPrevious(previousConfig);
            } else {
                GlobalLockConfigHolder.remove();
            }
        }
    }

    /**
     * 解析方法或类上的 @GlobalLock 注解参数，结合配置属性构建 GlobalLockConfig
     *
     * @param joinPoint AOP 连接点
     * @return GlobalLockConfig 实例
     */
    private GlobalLockConfig resolveGlobalLockConfig(ProceedingJoinPoint joinPoint) {
        int retryInterval = (int) seataProperties.getLock().getRetryInterval().toMillis();
        int retryTimes = seataProperties.getLock().getRetryTimes();

        if (joinPoint.getSignature() instanceof MethodSignature methodSignature) {
            Method method = methodSignature.getMethod();
            Class<?> targetClass = joinPoint.getTarget() != null ? joinPoint.getTarget().getClass() : method.getDeclaringClass();
            Method specificMethod = org.springframework.aop.support.AopUtils.getMostSpecificMethod(method, targetClass);

            // 1. 尝试解析 org.apache.seata.spring.annotation.GlobalLock
            org.apache.seata.spring.annotation.GlobalLock apacheLock =
                    AnnotatedElementUtils.findMergedAnnotation(specificMethod, org.apache.seata.spring.annotation.GlobalLock.class);
            if (apacheLock == null && specificMethod != method) {
                apacheLock = AnnotatedElementUtils.findMergedAnnotation(method, org.apache.seata.spring.annotation.GlobalLock.class);
            }
            if (apacheLock == null) {
                apacheLock = AnnotatedElementUtils.findMergedAnnotation(targetClass, org.apache.seata.spring.annotation.GlobalLock.class);
            }
            if (apacheLock != null) {
                if (apacheLock.lockRetryInterval() > 0) {
                    retryInterval = apacheLock.lockRetryInterval();
                }
                if (apacheLock.lockRetryTimes() >= 0) {
                    retryTimes = apacheLock.lockRetryTimes();
                }
            } else {
                // 2. 尝试解析 io.seata.spring.annotation.GlobalLock
                io.seata.spring.annotation.GlobalLock ioLock =
                        AnnotatedElementUtils.findMergedAnnotation(specificMethod, io.seata.spring.annotation.GlobalLock.class);
                if (ioLock == null && specificMethod != method) {
                    ioLock = AnnotatedElementUtils.findMergedAnnotation(method, io.seata.spring.annotation.GlobalLock.class);
                }
                if (ioLock == null) {
                    ioLock = AnnotatedElementUtils.findMergedAnnotation(targetClass, io.seata.spring.annotation.GlobalLock.class);
                }
                if (ioLock != null) {
                    if (ioLock.lockRetryInterval() > 0) {
                        retryInterval = ioLock.lockRetryInterval();
                    }
                    if (ioLock.lockRetryTimes() >= 0) {
                        retryTimes = ioLock.lockRetryTimes();
                    }
                }
            }
        }

        GlobalLockConfig config = new GlobalLockConfig();
        config.setLockRetryInterval(retryInterval);
        config.setLockRetryTimes(retryTimes);
        return config;
    }
}
