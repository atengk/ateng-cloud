package io.github.atengk.cloud.sample.feign.client;

import io.github.atengk.cloud.feign.annotation.AdaptiveUnwrap;
import io.github.atengk.cloud.sample.feign.model.UserVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 类级别标注 @AdaptiveUnwrap(false) 禁用解包的 Feign 测试客户端
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FeignClient(name = "class-level-excluded-client", url = "http://localhost:${test.server.port:8080}")
@AdaptiveUnwrap(false)
public interface ClassLevelExcludedFeignClient {

    /**
     * 接口类级标注 @AdaptiveUnwrap(false)，直接反序列化为 UserVO
     *
     * @return 业务实体
     */
    @GetMapping("/api/mock/adaptive/third-party/data")
    UserVO getThirdPartyData();
}
