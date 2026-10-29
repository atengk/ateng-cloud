package io.github.atengk.cloud.sample.sentinel.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import io.github.atengk.boot.core.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sentinel 场景演示测试控制器
 *
 * @author Ateng
 * @since 2026-10-29
 */
@RestController
@RequestMapping("/api/test")
public class SampleSentinelController {

    @GetMapping("/flow")
    public Result<String> testFlow() {
        return Result.success("flow-pass");
    }

    @GetMapping("/degrade")
    public Result<String> testDegrade(@RequestParam(name = "fail", defaultValue = "false") boolean fail) {
        if (fail) {
            throw new IllegalStateException("Simulated business exception for degrade");
        }
        return Result.success("degrade-pass");
    }

    @GetMapping("/users/{id}")
    public Result<String> getUserById(@PathVariable("id") Long id) {
        return Result.success("user-" + id);
    }

    @GetMapping("/admin/secure")
    public Result<String> adminSecure() {
        return Result.success("admin-ok");
    }

    /**
     * 测试无 blockHandler 的 @SentinelResource 普通注解方法 (由 SentinelBlockExceptionAdvice 兜底拦截)
     */
    @GetMapping("/annotation-resource")
    @SentinelResource(value = "custom-annotation-resource")
    public Result<String> testAnnotationResource() {
        return Result.success("annotation-resource-pass");
    }

    /**
     * 测试无 blockHandler 的 @SentinelResource 热点参数限流方法
     */
    @GetMapping("/param-flow")
    @SentinelResource(value = "custom-param-resource")
    public Result<String> testParamFlow(@RequestParam("itemId") String itemId) {
        return Result.success("item-" + itemId);
    }
}
