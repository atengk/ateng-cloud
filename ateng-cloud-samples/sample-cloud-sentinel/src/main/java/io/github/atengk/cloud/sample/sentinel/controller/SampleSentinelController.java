package io.github.atengk.cloud.sample.sentinel.controller;

import io.github.atengk.boot.core.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
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
}
