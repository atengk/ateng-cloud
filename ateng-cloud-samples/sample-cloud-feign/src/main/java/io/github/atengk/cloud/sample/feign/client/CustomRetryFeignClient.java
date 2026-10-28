package io.github.atengk.cloud.sample.feign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 具有单 Client 级独立重试参数覆盖的 Feign 测试客户端
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FeignClient(name = "custom-retry-client", url = "http://localhost:${test.unreachable.port:59999}")
public interface CustomRetryFeignClient {

    /**
     * 调用不可达端口测试独立重试参数（按配置弹性重试 2 次，共 3 次尝试）
     *
     * @return 响应报文
     */
    @GetMapping("/api/mock/retry/unreachable")
    String getUnreachable();
}
