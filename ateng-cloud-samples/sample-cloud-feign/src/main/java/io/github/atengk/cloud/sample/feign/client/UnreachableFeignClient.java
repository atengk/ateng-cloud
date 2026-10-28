package io.github.atengk.cloud.sample.feign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 不可达端口验证客户端
 * 指向未监听的端口，验证 GET 建连超时与弹性重试 1 次
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FeignClient(name = "unreachable-client", url = "http://127.0.0.1:${test.unreachable.port:59999}")
public interface UnreachableFeignClient {

    @GetMapping("/mock/retry/unreachable")
    String getUnreachable();
}
