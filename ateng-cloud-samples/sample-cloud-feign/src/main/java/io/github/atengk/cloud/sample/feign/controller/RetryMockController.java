package io.github.atengk.cloud.sample.feign.controller;

import io.github.atengk.boot.core.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Feign 重试与超时 Mock 控制器
 *
 * @author Ateng
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/mock/retry")
public class RetryMockController {

    private final AtomicInteger postCallCount = new AtomicInteger(0);
    private final AtomicInteger getSlowCallCount = new AtomicInteger(0);

    @PostMapping("/post-timeout")
    public Result<String> postTimeout() throws InterruptedException {
        postCallCount.incrementAndGet();
        // 模拟高负荷写操作耗时 1200ms，触发调用端 400ms 的读超时
        Thread.sleep(1200);
        return Result.success("post timeout completed");
    }

    @GetMapping("/slow")
    public Result<String> slow() throws InterruptedException {
        getSlowCallCount.incrementAndGet();
        // 模拟耗时 800ms
        Thread.sleep(800);
        return Result.success("slow completed");
    }

    public int getPostCallCount() {
        return postCallCount.get();
    }

    public int getGetSlowCallCount() {
        return getSlowCallCount.get();
    }

    public void reset() {
        postCallCount.set(0);
        getSlowCallCount.set(0);
    }
}
