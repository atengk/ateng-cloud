package io.github.atengk.cloud.sample.feign.client;

import io.github.atengk.cloud.feign.annotation.AdaptiveUnwrap;
import io.github.atengk.cloud.sample.feign.model.UserVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 客户端名称属于 exclude-clients 白名单，但通过类级 @AdaptiveUnwrap(true) 强制启用剥壳的 Feign 测试客户端
 * 验证：声明式注解优先级高于全局白名单与配置
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FeignClient(name = "excluded-client", contextId = "forcedAdaptiveFeignClient", url = "http://localhost:${test.server.port:8080}")
@AdaptiveUnwrap(true)
public interface ForcedAdaptiveFeignClient {

    /**
     * 虽属于 excluded-client，但类级 @AdaptiveUnwrap(true) 强制剥壳
     *
     * @param id 用户 ID
     * @return 业务实体
     */
    @GetMapping("/api/mock/adaptive/user/{id}")
    UserVO getUserById(@PathVariable("id") Long id);
}
