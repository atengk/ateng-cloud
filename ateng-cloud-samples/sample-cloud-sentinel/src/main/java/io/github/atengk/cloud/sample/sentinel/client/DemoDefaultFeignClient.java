package io.github.atengk.cloud.sample.sentinel.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 演示默认模式 Feign 客户端 (未配置对端服务，依赖 AtengFallbackFactory 默认异常转译)
 *
 * @author Ateng
 * @since 2026-10-29
 */
@FeignClient(name = "demo-default-client", url = "http://127.0.0.1:18099", fallbackFactory = DemoDefaultFallbackFactory.class)
public interface DemoDefaultFeignClient {

    @GetMapping("/api/remote/user/{id}")
    String getRemoteUser(@PathVariable("id") Long id);
}
