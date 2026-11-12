package io.github.atengk.cloud.sample.feign.controller;

import io.github.atengk.boot.core.model.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 远程微服务容灾与防御对端提供者
 * 模拟下游慢响应超时、业务错误码、HTTP 500 异常、超大载荷及上下文标头回显
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/feign/remote/tolerance")
public class RemoteToleranceProviderController {

    private final AtomicInteger postCallCount = new AtomicInteger(0);
    private final AtomicInteger getSlowCallCount = new AtomicInteger(0);

    /**
     * 1. 模拟耗时 1200ms 的非幂等写操作，用于验证调用端 400ms 读超时且绝不重试
     */
    @PostMapping("/post-timeout")
    public Result<String> postTimeout() throws InterruptedException {
        postCallCount.incrementAndGet();
        Thread.sleep(1200);
        return Result.success("post timeout completed");
    }

    /**
     * 2. 模拟耗时 1200ms 的只读操作，用于验证读超时不发起重试
     */
    @GetMapping("/get-timeout")
    public Result<String> getTimeout() throws InterruptedException {
        getSlowCallCount.incrementAndGet();
        Thread.sleep(1200);
        return Result.success("get timeout completed");
    }

    /**
     * 3. 模拟耗时 800ms，用于验证单 Client 独立 300ms 超时隔离
     */
    @GetMapping("/slow")
    public Result<String> slow() throws InterruptedException {
        Thread.sleep(800);
        return Result.success("slow completed");
    }

    /**
     * 4. 模拟耗时 180ms（小于 5s 读超时但超过客户端 100ms 慢调用阈值）的正常接口，用于验证慢调用 WARN 告警
     */
    @GetMapping("/slow-ok")
    public Result<String> slowOk() throws InterruptedException {
        Thread.sleep(180);
        return Result.success("slow-ok completed");
    }

    /**
     * 5. 模拟下游返回业务错误码 6001
     */
    @GetMapping("/business-error")
    public Result<String> businessError() {
        return Result.failure(6001, "下游支付通道结算失败，账户已被冻结");
    }

    /**
     * 6. 模拟下游崩溃返回 HTTP 500
     */
    @GetMapping("/raw-500")
    public ResponseEntity<Result<String>> raw500() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(500, "下游第三方银行网关系统崩溃"));
    }

    /**
     * 7. 模拟下游返回 11MB 超大响应体，用于触发客户端 10MB 响应载荷防爆熔断
     */
    @GetMapping("/large-payload")
    public String largePayload() {
        byte[] payload = new byte[11 * 1024 * 1024];
        return new String(payload, StandardCharsets.ISO_8859_1);
    }

    /**
     * 8. 回显调用端注入的出站标头，用于验证四维上下文与 Same-Token 透传
     */
    @GetMapping("/echo-headers")
    public Result<Map<String, String>> echoHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                headers.put(name.toLowerCase(), request.getHeader(name));
            }
        }
        return Result.success(headers);
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
