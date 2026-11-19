package io.github.atengk.cloud.sample.smoke.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.smoke.model.SmokeProductVO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全家桶 Feign + Sentinel 联动冒烟模拟下游服务端控制器
 * <p>
 * 仅作为 Feign 双模解包与异常状态码解码测试的模拟下游桩端点（涵盖裸 DTO、标准 Result 及 HTTP 500 响应），
 * 非对外业务控制器契约范式。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
@RestController
@RequestMapping("/sample/cloud/smoke/feign")
public class SmokeFeignProviderController {

    /**
     * 返回裸 DTO 对象（测试自适应解包平滑兼容裸体模式）
     *
     * @param id 商品 ID
     * @return 商品视图对象
     */
    @GetMapping("/dto/{id}")
    public SmokeProductVO getBareDto(@PathVariable("id") Long id) {
        return new SmokeProductVO(id, "Smoke-Bare-Product-" + id, 88);
    }

    /**
     * 返回标准 Result&lt;T&gt;（测试自适应解包自动剥壳）
     *
     * @param id 商品 ID
     * @return 标准 Result 包装响应
     */
    @GetMapping("/result/{id}")
    public Result<SmokeProductVO> getWrappedResult(@PathVariable("id") Long id) {
        return Result.success(new SmokeProductVO(id, "Smoke-Result-Product-" + id, 99));
    }

    /**
     * 返回非 200 业务失败 Result（携带远端 traceId）
     *
     * @return 业务失败 Result
     */
    @GetMapping("/biz-error")
    public Result<SmokeProductVO> getBusinessError() {
        return Result.<SmokeProductVO>fail(4221, "库存不足").traceId("smoke-biz-trace-001");
    }

    /**
     * 模拟下游 500 故障触发 Sentinel FallbackFactory 熔断降级
     *
     * @return 500 错误响应
     */
    @GetMapping("/server-error")
    public ResponseEntity<Result<SmokeProductVO>> getServerError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.<SmokeProductVO>fail(500, "下游服务异常").traceId("smoke-err-trace-002"));
    }
}
