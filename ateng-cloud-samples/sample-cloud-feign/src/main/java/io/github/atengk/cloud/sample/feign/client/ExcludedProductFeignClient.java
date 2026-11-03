package io.github.atengk.cloud.sample.feign.client;

import io.github.atengk.cloud.sample.feign.model.ProductDetailVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 白名单排除客户端测试 Feign 接口 (命中 ateng.cloud.feign.adaptive-unwrap.exclude-clients)
 *
 * @author Ateng
 * @since 2026-11-03
 */
@FeignClient(name = "excluded-client", url = "http://localhost:${test.server.port:8080}")
public interface ExcludedProductFeignClient {

    /**
     * 调用返回未封装裸 JSON 的对端接口，因客户端在 exclude-clients 白名单中，跳过 Result 剥壳，直接反序列化为实体
     */
    @GetMapping("/sample/cloud/feign/remote/product/third-party/{id}")
    ProductDetailVO getThirdPartyProduct(@PathVariable("id") Long id);
}
