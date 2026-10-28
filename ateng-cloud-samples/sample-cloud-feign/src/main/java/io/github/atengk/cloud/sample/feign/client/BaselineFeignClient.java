package io.github.atengk.cloud.sample.feign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 基础连通性 Feign 测试客户端
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FeignClient(name = "baseline-client", url = "http://localhost:${test.server.port:8080}")
public interface BaselineFeignClient {

    @GetMapping("/api/mock/echo")
    String echo(@RequestParam("msg") String msg);
}
