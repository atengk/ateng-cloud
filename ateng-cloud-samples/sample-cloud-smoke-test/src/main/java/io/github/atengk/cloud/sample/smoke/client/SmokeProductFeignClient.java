package io.github.atengk.cloud.sample.smoke.client;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.smoke.model.SmokeProductVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 全家桶无降级直调 Feign 客户端（验证与 Sentinel 共存时无 UnsupportedOperationException 且支持双模解包与异常剥壳）
 *
 * @author Ateng
 * @since 2026-11-19
 */
@FeignClient(
        name = "smoke-product-client",
        url = "http://127.0.0.1:${server.port:8080}/sample/cloud/smoke/feign"
)
public interface SmokeProductFeignClient {

    /**
     * 调用下游裸 DTO 接口
     *
     * @param id 商品 ID
     * @return 商品视图对象
     */
    @GetMapping("/dto/{id}")
    SmokeProductVO getBareDto(@PathVariable("id") Long id);

    /**
     * 调用下游 Result 包装接口（自动剥壳）
     *
     * @param id 商品 ID
     * @return 商品视图对象
     */
    @GetMapping("/result/{id}")
    SmokeProductVO getUnwrappedResult(@PathVariable("id") Long id);

    /**
     * 声明返回原生 Result（透传不剥壳）
     *
     * @param id 商品 ID
     * @return 原始 Result 包装对象
     */
    @GetMapping("/result/{id}")
    Result<SmokeProductVO> getRawResult(@PathVariable("id") Long id);

    /**
     * 调用下游返回非 200 Result 接口（验证抛出裸 BusinessException 且携带 traceId）
     *
     * @return 商品视图对象
     */
    @GetMapping("/biz-error")
    SmokeProductVO getBusinessError();
}
