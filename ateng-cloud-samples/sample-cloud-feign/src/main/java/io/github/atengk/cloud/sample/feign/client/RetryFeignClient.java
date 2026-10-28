package io.github.atengk.cloud.sample.feign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Feign 重试防雪崩验证客户端
 * 配置有独立的短读超时 (socket-timeout: 400ms)
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FeignClient(name = "retry-client", url = "http://localhost:${test.server.port}")
public interface RetryFeignClient {

    @PostMapping("/mock/retry/post-timeout")
    String postTimeout();
}
