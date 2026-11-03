package io.github.atengk.cloud.sample.feign.client;

import io.github.atengk.cloud.feign.annotation.AdaptiveUnwrap;
import io.github.atengk.cloud.sample.feign.model.ProductDetailVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 客户端名称命中 exclude-clients 白名单，但类级标注 @AdaptiveUnwrap(true) 强制剥壳（注解优先级最高）
 *
 * @author Ateng
 * @since 2026-11-03
 */
@FeignClient(name = "excluded-client", contextId = "forcedAdaptiveProductFeignClient", url = "http://localhost:${test.server.port:8080}")
@AdaptiveUnwrap(true)
public interface ForcedAdaptiveProductFeignClient {

    /**
     * 强行开启剥壳，解析 Result&lt;ProductDetailVO&gt; 并返回业务对象
     */
    @GetMapping("/sample/cloud/feign/remote/product/{id}")
    ProductDetailVO getProductById(@PathVariable("id") Long id);
}
