package io.github.atengk.cloud.sample.feign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

/**
 * 上下文透传与标头回传校验 Feign 客户端
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FeignClient(name = "context-client", url = "http://localhost:${test.server.port}")
public interface ContextFeignClient {

    @GetMapping("/mock/context/echo-headers")
    Map<String, String> echoHeaders();
}
