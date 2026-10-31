package io.github.atengk.cloud.traffic.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式流量染色与泳道绑定注解
 * <p>
 * 支持标注于方法或类级别，自动为当前执行方法绑定指定流量染色标签。
 * 方法级注解优先级高于类级注解。支持配置静态常数字符串或 Spring 环境属性占位符（如 {@code ${canary.tag:v2}}）。
 * 严守确定性生命周期治理：方法执行完毕后在 {@code finally} 块中 100% 安全还原先前的上下文，杜绝线程污染。
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface TrafficTag {

    /**
     * 绑定的流量染色标签 (别名)
     *
     * @return 流量染色标签
     */
    @AliasFor("tag")
    String value() default "";

    /**
     * 绑定的流量染色标签，支持静态字符串或 ${...} 占位符
     *
     * @return 流量染色标签
     */
    @AliasFor("value")
    String tag() default "";

    /**
     * 是否强制覆盖当前已有标签
     * <p>
     * true (默认): 强制切换为当前注解指定标签并在方法退出时还原；
     * false: 若当前上下文已有有效标签则保留继承，仅在当前无标签时绑定新标签。
     *
     * @return 是否强制覆盖
     */
    boolean override() default true;
}
