package io.github.atengk.cloud.seata.aspect;

import io.github.atengk.cloud.seata.exception.SeataExceptionTranslator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;

/**
 * Seata 分布式事务异常因果转译切面
 * <p>
 * 围绕标注 @GlobalTransactional 与 @GlobalLock 的核心业务方法，拦截并转译底层 Seata 驱动异常，
 * 消除原始 SDK 异常向业务上层与传输层泄漏的隐患，保障因果链 100% 完整。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Aspect
public class SeataExceptionTranslationAspect implements Ordered {

    /**
     * 切面执行顺序：优先于 Seata 默认拦截器捕获外部逃逸异常
     */
    private int order = Ordered.LOWEST_PRECEDENCE - 10;

    @Around("@annotation(org.apache.seata.spring.annotation.GlobalTransactional) " +
            "|| @within(org.apache.seata.spring.annotation.GlobalTransactional) " +
            "|| @annotation(org.apache.seata.spring.annotation.GlobalLock) " +
            "|| @within(org.apache.seata.spring.annotation.GlobalLock)")
    public Object translateTransactionalException(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (Throwable ex) {
            throw SeataExceptionTranslator.translate(ex);
        }
    }

    @Override
    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }
}
