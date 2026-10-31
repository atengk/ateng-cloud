package io.github.atengk.cloud.traffic.aspect;

import io.github.atengk.cloud.traffic.annotation.TrafficTag;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.util.StringValueResolver;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * 声明式流量染色与泳道绑定切面
 * <p>
 * 遵循微服务流量治理铁律：
 * 1. 自动拦截标注了 {@link TrafficTag} 的方法或类，方法级注解优先级高于类级注解；
 * 2. 纳秒级轻量解析静态字符串或 {@code ${...}} 属性占位符，坚决杜绝引入 SpEL 以防范 RCE 安全漏洞；
 * 3. 遵从 {@code override} 属性控制覆盖或继承语义：为 true 时强制切换为新标签并在出口恢复原上下文，为 false 时若当前已有有效标签则遵循继承；
 * 4. 严守确定性生命周期治理不变量：在 {@code finally} 块中 100% 确定性还原上级上下文与 MDC 标记，彻底杜绝工作线程复用污染。
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Aspect
public class TrafficTagAspect implements EmbeddedValueResolverAware, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TrafficTagAspect.class);

    private final Environment environment;
    private StringValueResolver valueResolver;
    private int order = Ordered.HIGHEST_PRECEDENCE + 50;

    public TrafficTagAspect() {
        this(null);
    }

    public TrafficTagAspect(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        this.valueResolver = resolver;
    }

    @Override
    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    /**
     * 环绕拦截带有 {@link TrafficTag} 注解的方法与类
     *
     * @param joinPoint 连接点
     * @return 方法执行结果
     * @throws Throwable 业务异常
     */
    @Around("@annotation(io.github.atengk.cloud.traffic.annotation.TrafficTag) || @within(io.github.atengk.cloud.traffic.annotation.TrafficTag)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!(joinPoint.getSignature() instanceof MethodSignature methodSignature)) {
            return joinPoint.proceed();
        }

        Method method = methodSignature.getMethod();
        Class<?> targetClass = (joinPoint.getTarget() != null)
                ? joinPoint.getTarget().getClass()
                : joinPoint.getSignature().getDeclaringType();

        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);

        // 1. 优先读取方法级注解，缺失时回退至类级注解 (方法级优先)
        TrafficTag annotation = AnnotatedElementUtils.findMergedAnnotation(specificMethod, TrafficTag.class);
        if (annotation == null && targetClass != null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(targetClass, TrafficTag.class);
        }

        if (annotation == null) {
            return joinPoint.proceed();
        }

        // 2. 提取并安全解析标签值 (支持静态文本与 ${...} 属性占位符)
        String rawTag = StringUtils.hasText(annotation.tag()) ? annotation.tag() : annotation.value();
        String resolvedTag = resolveTag(rawTag);

        if (!StringUtils.hasText(resolvedTag)) {
            return joinPoint.proceed();
        }

        String targetTag = resolvedTag.trim();
        String currentTag = TrafficContextHolder.getTag();
        boolean hasCurrentTag = StringUtils.hasText(currentTag);

        // 3. 判定继承与覆盖语义：若配置 override=false 且当前线程已存在有效标签，保留继承原标签
        if (!annotation.override() && hasCurrentTag) {
            if (log.isDebugEnabled()) {
                log.debug("[TrafficAspect] 当前已有流量标签且 override=false，继承上级标签: currentTag='{}', ignoredTag='{}', method='{}'",
                        currentTag, targetTag, specificMethod.getName());
            }
            return joinPoint.proceed();
        }

        // 4. 执行上下文切换并在 finally 中 100% 确定性安全还原
        TrafficContext previousContext = TrafficContextHolder.getContext();
        String previousMdc = MDC.get(TrafficConstants.MDC_TRAFFIC_TAG);

        if (log.isDebugEnabled()) {
            log.debug("[TrafficAspect] 声明式切面激活流量标签: targetTag='{}', previousTag='{}', method='{}'",
                    targetTag, currentTag, specificMethod.getName());
        }

        try {
            TrafficContextHolder.setTag(targetTag);
            return joinPoint.proceed();
        } finally {
            if (previousContext != null) {
                TrafficContextHolder.setContext(previousContext);
            } else {
                TrafficContextHolder.clear();
            }
            if (previousMdc != null) {
                MDC.put(TrafficConstants.MDC_TRAFFIC_TAG, previousMdc);
            } else {
                MDC.remove(TrafficConstants.MDC_TRAFFIC_TAG);
            }
        }
    }

    /**
     * 解析标签字符串，支持嵌入式占位符解析
     *
     * @param rawTag 原始标签字符串
     * @return 解析后的标签字符串
     */
    private String resolveTag(String rawTag) {
        if (!StringUtils.hasText(rawTag)) {
            return null;
        }
        String trimmed = rawTag.trim();
        if (valueResolver != null) {
            try {
                String resolved = valueResolver.resolveStringValue(trimmed);
                if (StringUtils.hasText(resolved)) {
                    return resolved.trim();
                }
            } catch (Exception ex) {
                log.warn("[TrafficAspect] 解析 @TrafficTag 占位符异常，回退原始值 [tag='{}', cause={}]", trimmed, ex.getMessage());
            }
        }
        if (environment != null) {
            try {
                String resolved = environment.resolvePlaceholders(trimmed);
                if (StringUtils.hasText(resolved)) {
                    return resolved.trim();
                }
            } catch (Exception ex) {
                log.warn("[TrafficAspect] 通过 Environment 解析 @TrafficTag 占位符异常 [tag='{}', cause={}]", trimmed, ex.getMessage());
            }
        }
        return trimmed;
    }
}
