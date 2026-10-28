package io.github.atengk.cloud.sample.feign.client;

import io.github.atengk.cloud.sample.feign.model.UserVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 白名单排除客户端测试 Feign 接口
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FeignClient(name = "excluded-client", url = "http://localhost:${test.server.port:8080}")
public interface ExcludedFeignClient {

    @GetMapping("/api/mock/adaptive/third-party/data")
    UserVO getThirdPartyData();
}
