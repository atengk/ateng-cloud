package io.github.atengk.cloud.feign.logger;

import feign.Logger;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import org.springframework.cloud.openfeign.FeignLoggerFactory;

/**
 * 生产级安全脱敏 Feign 日志工厂
 * <p>
 * 替换 Spring Cloud OpenFeign 默认日志工厂，为每一个 FeignClient 实例
 * 生产挂载机密安全脱敏与慢调用告警能力的 {@link AtengFeignLogger}。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignLoggerFactory implements FeignLoggerFactory {

    private final AtengFeignProperties properties;

    public AtengFeignLoggerFactory() {
        this(null);
    }

    public AtengFeignLoggerFactory(AtengFeignProperties properties) {
        this.properties = properties;
    }

    @Override
    public Logger create(Class<?> type) {
        return new AtengFeignLogger(type, properties);
    }
}
