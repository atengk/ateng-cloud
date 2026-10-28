package io.github.atengk.cloud.sample.feign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 单 Client 独立隔离超时验证客户端
 * 独立配置短读超时 (socket-timeout: 300ms)
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FeignClient(name = "isolated-timeout-client", url = "http://localhost:${test.server.port}")
public interface IsolatedTimeoutFeignClient {

    @GetMapping("/mock/retry/slow")
    String slow();
}
