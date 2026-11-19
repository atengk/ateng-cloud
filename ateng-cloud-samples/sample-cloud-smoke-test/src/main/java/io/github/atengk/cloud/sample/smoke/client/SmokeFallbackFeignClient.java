package io.github.atengk.cloud.sample.smoke.client;

import io.github.atengk.cloud.sample.smoke.model.SmokeProductVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 全家桶受管 Sentinel 降级 Feign 客户端（验证与 AtengFallbackFactory 协同熔断降级与业务异常透传）
 *
 * @author Ateng
 * @since 2026-11-19
 */
@FeignClient(
        name = "smoke-fallback-client",
        url = "http://127.0.0.1:${server.port:8080}/sample/cloud/smoke/feign",
        fallbackFactory = SmokeFallbackFactory.class
)
public interface SmokeFallbackFeignClient {

    /**
     * 触发下游 500 异常验证 Sentinel Fallback 降级
     *
     * @return 商品视图对象
     */
    @GetMapping("/server-error")
    SmokeProductVO triggerServerError();

    /**
     * 触发下游非 200 业务异常验证 AtengFallbackFactory 原样透传 BusinessException
     *
     * @return 商品视图对象
     */
    @GetMapping("/biz-error")
    SmokeProductVO triggerBusinessError();
}
