package io.github.atengk.cloud.sample.smoke.client;

import io.github.atengk.cloud.sentinel.fallback.AtengFallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 全家桶冒烟验证受管 Sentinel Feign 降级工厂
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Component
public class SmokeFallbackFactory extends AtengFallbackFactory<SmokeFallbackFeignClient> {
}
