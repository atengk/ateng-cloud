package io.github.atengk.cloud.sample.sentinel.client;

import io.github.atengk.cloud.sentinel.fallback.AtengFallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 默认受管 Feign 降级工厂 (无需手动重写，默认抛出 BusinessException 4202 DEGRADE)
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Component
public class DemoDefaultFallbackFactory extends AtengFallbackFactory<DemoDefaultFeignClient> {
}
