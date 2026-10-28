package io.github.atengk.cloud.sample.gateway.controller;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

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

    @GetMapping("/protected")
    public Mono<Result<String>> protectedEndpoint() {
        return Mono.just(Result.success("protected-access-ok"));
    }

    @GetMapping("/echo-headers")
    public Mono<Result<Map<String, String>>> echoHeaders(ServerHttpRequest request) {
        Map<String, String> headerMap = new HashMap<>();
        request.getHeaders().forEach((name, values) -> {
            if (values != null && !values.isEmpty()) {
                headerMap.put(name.toLowerCase(), values.get(0));
            }
        });
        return Mono.just(Result.success(headerMap));
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
