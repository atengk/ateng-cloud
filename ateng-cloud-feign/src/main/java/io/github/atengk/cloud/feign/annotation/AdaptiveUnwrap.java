package io.github.atengk.cloud.feign.annotation;

import java.lang.annotation.*;

/**
 * Feign 自适应契约解包控制注解
 * 声明在 FeignClient 接口或具体方法上，用于显式启用或关闭 Result&lt;T&gt; 物理契约的自动剥壳行为
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface AdaptiveUnwrap {

    /**
     * 是否开启自适应解包，默认 true
     * 设为 false 时无论全局配置如何均不执行剥壳，原样反序列化或透传
     *
     * @return true 开启，false 关闭
     */
    boolean value() default true;
}
