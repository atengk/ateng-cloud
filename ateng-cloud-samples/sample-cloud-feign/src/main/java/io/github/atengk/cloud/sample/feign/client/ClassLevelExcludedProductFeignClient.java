package io.github.atengk.cloud.sample.feign.client;

import io.github.atengk.cloud.feign.annotation.AdaptiveUnwrap;
import io.github.atengk.cloud.sample.feign.model.ProductDetailVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 类级别标注 @AdaptiveUnwrap(false) 禁用解包的 Feign 测试客户端
 *
 * @author Ateng
 * @since 2026-11-03
 */
@FeignClient(name = "class-level-excluded-product-client", url = "http://localhost:${test.server.port:8080}")
@AdaptiveUnwrap(false)
public interface ClassLevelExcludedProductFeignClient {

    /**
     * 接口类级标注 @AdaptiveUnwrap(false)，整客户端直接反序列化为 ProductDetailVO
     */
    @GetMapping("/sample/cloud/feign/remote/product/third-party/{id}")
    ProductDetailVO getThirdPartyProduct(@PathVariable("id") Long id);
}
