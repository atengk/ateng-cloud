package io.github.atengk.cloud.sample.gateway.controller;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 响应式 Mock 测试终结点
 *
 * @author Ateng
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/mock")
public class MockTestController {

    private final AtomicInteger getRetryCounter = new AtomicInteger(0);
    private final AtomicInteger postRetryCounter = new AtomicInteger(0);

    public void resetRetryCounters() {
        getRetryCounter.set(0);
        postRetryCounter.set(0);
    }

    public int getPostRetryCount() {
        return postRetryCounter.get();
    }

    public int getGetRetryCount() {
        return getRetryCounter.get();
    }

    @GetMapping("/hello")
    public Mono<Result<String>> hello() {
        return Mono.just(Result.success("gateway-ok"));
    }

    @GetMapping("/open-data")
    public Mono<Result<String>> openData() {
        return Mono.just(Result.success("open-api-verified"));
    }

    @PostMapping("/open-data")
    public Mono<Result<Map<String, Object>>> openDataPost(@RequestBody(required = false) Mono<Map<String, Object>> bodyMono) {
        if (bodyMono == null) {
            return Mono.just(Result.success(Map.of("status", "empty-body")));
        }
        return bodyMono.map(Result::success).defaultIfEmpty(Result.success(Map.of("status", "empty-body")));
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

    @GetMapping("/premature-close")
    public Mono<Result<Void>> prematureClose() {
        return Mono.error(reactor.netty.http.client.PrematureCloseException.TEST_EXCEPTION);
    }

    @GetMapping("/closed-channel")
    public Mono<Result<Void>> closedChannel() {
        return Mono.error(new java.nio.channels.ClosedChannelException());
    }

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sse() {
        return Flux.range(0, 3)
                .delayElements(Duration.ofMillis(20))
                .map(seq -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(seq))
                        .event("message")
                        .data("chunk-" + seq)
                        .build());
    }

    @PostMapping("/upload-echo")
    public Mono<Result<Integer>> uploadEcho(@RequestBody Mono<byte[]> bodyMono) {
        return bodyMono.map(bytes -> Result.success(bytes.length));
    }

    @GetMapping("/simulate-rate-limit")
    public Mono<Void> simulateRateLimit(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        return response.setComplete();
    }

    @GetMapping("/retry-counter")
    public Mono<Result<Integer>> getRetry() throws Exception {
        int count = getRetryCounter.incrementAndGet();
        if (count == 1) {
            throw new ConnectException("模拟底层网络建连超时或连接拒绝");
        }
        return Mono.just(Result.success(count));
    }

    @PostMapping("/retry-counter")
    public Mono<Result<Integer>> postRetry() throws Exception {
        postRetryCounter.incrementAndGet();
        throw new ConnectException("模拟非幂等写操作底层建连失败");
    }

    @GetMapping("/satoken-not-login")
    public Mono<Void> saTokenNotLogin() {
        return Mono.error(new cn.dev33.satoken.exception.NotLoginException("未登录", cn.dev33.satoken.stp.StpUtil.TYPE, cn.dev33.satoken.exception.NotLoginException.NOT_TOKEN));
    }

    @GetMapping("/satoken-not-permission")
    public Mono<Void> saTokenNotPermission() {
        return Mono.error(new cn.dev33.satoken.exception.NotPermissionException("system:user:export"));
    }
}
