package io.github.atengk.cloud.sample.gateway.controller;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 响应式 Mock 测试终结点
 *
 * @author Ateng
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/mock")
public class MockTestController {

    @GetMapping("/hello")
    public Mono<Result<String>> hello() {
        return Mono.just(Result.success("gateway-ok"));
    }

    @GetMapping("/business-error")
    public Mono<Result<Void>> businessError() {
        throw new BusinessException(1001, "业务断言异常触发");
    }

    @GetMapping("/server-error")
    public Mono<Result<Void>> serverError() {
        throw new RuntimeException("底层未知数据库异常，禁止泄漏堆栈");
    }
}
