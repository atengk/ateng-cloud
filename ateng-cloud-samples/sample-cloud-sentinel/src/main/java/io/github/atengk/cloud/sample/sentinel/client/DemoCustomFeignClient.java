package io.github.atengk.cloud.sample.sentinel.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 演示柔性降级模式 Feign 客户端 (通过重写 handleFallback 返回保底 Mock 数据)
 *
 * @author Ateng
 * @since 2026-10-29
 */
@FeignClient(name = "demo-custom-client", url = "http://127.0.0.1:18099", fallbackFactory = DemoCustomFallbackFactory.class)
public interface DemoCustomFeignClient {

    @GetMapping("/api/remote/order/{id}")
    String getRemoteOrder(@PathVariable("id") Long id);
}
