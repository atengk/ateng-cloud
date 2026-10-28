package io.github.atengk.cloud.feign.logger;

import feign.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FeignLoggerFactory;

/**
 * 生产级安全脱敏 Feign 日志工厂
 * <p>
 * 替换 Spring Cloud OpenFeign 默认日志工厂，为每一个 FeignClient 实例
 * 生产挂载机密安全脱敏能力的 {@link AtengFeignLogger}。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignLoggerFactory implements FeignLoggerFactory {

    @Override
    public Logger create(Class<?> type) {
        return new AtengFeignLogger(type != null ? LoggerFactory.getLogger(type) : LoggerFactory.getLogger(AtengFeignLogger.class));
    }
}
