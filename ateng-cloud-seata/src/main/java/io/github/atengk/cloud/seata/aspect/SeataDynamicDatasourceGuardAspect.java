package io.github.atengk.cloud.seata.aspect;

import com.baomidou.dynamic.datasource.annotation.DS;
import io.github.atengk.cloud.seata.datasource.guard.SeataDynamicDatasourceGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.MethodClassKey;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/**
 * 声明式 {@link DS} 动态数据源假切源自动防卫切面
 * <p>
 * 默认声明最高优先级 {@link Ordered#HIGHEST_PRECEDENCE}，确保在 Baomidou {@code DynamicDataSourceAnnotationInterceptor}
 * 将新数据源 Key 压入线程栈之前，先基于当前 Spring 事务已绑定的初始数据源完成跨库假切源校验，
 * 避免 Spring 单连接事务静默忽略切源导致的串库资损风险。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SeataDynamicDatasourceGuardAspect implements Ordered {

    private static final String EMPTY_DS_KEY = "";

    private final SeataDynamicDatasourceGuard guard;

    private final Map<MethodClassKey, String> dsKeyCache = new ConcurrentReferenceHashMap<>(64);

    /**
     * 构造声明式 {@link DS} 假切源防卫切面
     *
     * @param guard 动态数据源假切源守卫实例，非空
     */
    public SeataDynamicDatasourceGuardAspect(SeataDynamicDatasourceGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard 必须非空");
    }

    /**
     * 在 {@link DS} 标注方法或类执行前执行假切源前置安全校验
     *
     * @param joinPoint AOP 环绕切入点上下文
     * @return 目标方法返回值
     * @throws Throwable 目标方法抛出的异常或校验阻断异常
     */
    @Around("@annotation(com.baomidou.dynamic.datasource.annotation.DS) || @within(com.baomidou.dynamic.datasource.annotation.DS)")
    public Object guardDataSourceSwitch(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!(joinPoint.getSignature() instanceof MethodSignature signature)) {
            return joinPoint.proceed();
        }
        Method method = signature.getMethod();
        Class<?> targetClass = joinPoint.getTarget() != null
                ? joinPoint.getTarget().getClass()
                : method.getDeclaringClass();
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);

        String dsKey = resolveCachedDsKey(specificMethod, method, targetClass);
        if (StringUtils.hasText(dsKey)) {
            guard.validateSwitch(dsKey, specificMethod, targetClass);
        }
        return joinPoint.proceed();
    }

    private String resolveCachedDsKey(Method specificMethod, Method declaredMethod, Class<?> targetClass) {
        MethodClassKey cacheKey = new MethodClassKey(specificMethod, targetClass);
        return dsKeyCache.computeIfAbsent(cacheKey, key -> {
            DS dsAnnotation = resolveDsAnnotation(specificMethod, declaredMethod, targetClass);
            if (dsAnnotation == null || !StringUtils.hasText(dsAnnotation.value())) {
                return EMPTY_DS_KEY;
            }
            return dsAnnotation.value();
        });
    }

    private DS resolveDsAnnotation(Method specificMethod, Method declaredMethod, Class<?> targetClass) {
        DS ds = AnnotatedElementUtils.findMergedAnnotation(specificMethod, DS.class);
        if (ds != null) {
            return ds;
        }
        if (!specificMethod.equals(declaredMethod)) {
            ds = AnnotatedElementUtils.findMergedAnnotation(declaredMethod, DS.class);
            if (ds != null) {
                return ds;
            }
        }
        for (Class<?> iface : ClassUtils.getAllInterfacesForClassAsSet(targetClass)) {
            Method ifaceMethod = ReflectionUtils.findMethod(iface, specificMethod.getName(), specificMethod.getParameterTypes());
            if (ifaceMethod != null) {
                ds = AnnotatedElementUtils.findMergedAnnotation(ifaceMethod, DS.class);
                if (ds != null) {
                    return ds;
                }
            }
        }
        ds = AnnotatedElementUtils.findMergedAnnotation(targetClass, DS.class);
        if (ds != null) {
            return ds;
        }
        Class<?> declaringClass = declaredMethod.getDeclaringClass();
        return declaringClass != targetClass
                ? AnnotatedElementUtils.findMergedAnnotation(declaringClass, DS.class)
                : null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
