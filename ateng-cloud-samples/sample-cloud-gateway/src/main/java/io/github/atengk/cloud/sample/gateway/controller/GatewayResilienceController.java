package io.github.atengk.cloud.sample.gateway.controller;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.gateway.model.InventoryStockVO;
import io.github.atengk.cloud.sample.gateway.model.RetryStatVO;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网关微服务弹性容灾与统一异常转译控制器 (100% 响应式 WebFlux)
 *
 * <p>涵盖以下分布式微服务容灾与治理场景：
 * <ul>
 *     <li>只读幂等查询（库存查询）建连失败弹性重试 1 次恢复成功；</li>
 *     <li>非幂等写操作（订单创建）建连失败绝对零重试防雪崩；</li>
 *     <li>高并发秒杀突发流量触发 HTTP 429 与 Retry-After 标头附加；</li>
 *     <li>下游滚动重启连接断开（PrematureClose / ClosedChannel）转译为 HTTP 502 Bad Gateway；</li>
 *     <li>订单实时物流追踪事件流（SSE, Server-Sent Events）响应式直通零缓冲推送；</li>
 *     <li>受管业务异常 BusinessException 错误码保真透传；</li>
 *     <li>未知系统异常脱敏掩码与防敏感堆栈泄露；</li>
 *     <li>Sentinel 流控、熔断降级、授权与系统保护异常语义化转译。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/gateway/resilience")
public class GatewayResilienceController {

    private final AtomicInteger getRetryCounter = new AtomicInteger(0);
    private final AtomicInteger postRetryCounter = new AtomicInteger(0);

    /**
     * 重置弹性重试计数器 (供测试或观测使用)
     */
    public void resetRetryCounters() {
        getRetryCounter.set(0);
        postRetryCounter.set(0);
    }

    public int getGetRetryCount() {
        return getRetryCounter.get();
    }

    public int getPostRetryCount() {
        return postRetryCounter.get();
    }

    /**
     * 只读库存查询接口 (模拟首次建连失败，弹性重试第 2 次成功返回)
     *
     * @param skuCode 商品 SKU 编码
     * @return 包含调用重试次数的库存强类型视图
     * @throws ConnectException 首次调用模拟底层网络建连超时
     */
    @GetMapping("/query-inventory")
    public Mono<Result<InventoryStockVO>> queryInventory(
            @RequestParam(value = "skuCode", defaultValue = "SKU-IPHONE-16") String skuCode) throws Exception {
        int count = getRetryCounter.incrementAndGet();
        if (count == 1) {
            throw new ConnectException("模拟底层库存服务网络建连超时或连接拒绝");
        }
        return Mono.just(Result.success(new InventoryStockVO(skuCode, 99, count, "SUCCESS_AFTER_RETRY")));
    }

    /**
     * 非幂等订单创建接口 (模拟底层写操作建连失败，严格禁止重试，执行次数必须为 1)
     *
     * @return 容灾统计视图
     * @throws ConnectException 模拟非幂等写操作底层建连失败
     */
    @PostMapping("/create-order")
    public Mono<Result<RetryStatVO>> createOrder() throws Exception {
        postRetryCounter.incrementAndGet();
        throw new ConnectException("模拟非幂等订单创建底层建连失败，严格禁止重试防雪崩");
    }

    /**
     * 秒杀突发流量限流接口 (模拟限流触发 HTTP 429 并转译 Retry-After: 1 标头)
     *
     * @param response 响应式 HTTP 响应
     * @return 响应式完成信号
     */
    @GetMapping("/seckill-spike")
    public Mono<Void> seckillSpike(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        return response.setComplete();
    }

    /**
     * 下游节点滚动重启中断模拟接口 (转译为 HTTP 502 Bad Gateway)
     *
     * @param type 异常类型 (premature 或 closed)
     * @return 响应式异常信号
     */
    @GetMapping("/simulate-node-restart")
    public Mono<Result<Void>> simulateNodeRestart(
            @RequestParam(value = "type", defaultValue = "premature") String type) {
        if ("closed".equalsIgnoreCase(type)) {
            return Mono.error(new java.nio.channels.ClosedChannelException());
        }
        return Mono.error(reactor.netty.http.client.PrematureCloseException.TEST_EXCEPTION);
    }

    /**
     * 订单物流实时状态变更事件流 (Server-Sent Events 响应式直通推送)
     *
     * @return SSE 事件数据流
     */
    @GetMapping(value = "/order-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> orderEvents() {
        return Flux.range(0, 3)
                .delayElements(Duration.ofMillis(20))
                .map(seq -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(seq))
                        .event("order-track")
                        .data("chunk-" + seq)
                        .build());
    }

    /**
     * 受管业务异常模拟终结点 (错误码与描述保真透传)
     *
     * @return 异常结果
     */
    @GetMapping("/business-error")
    public Mono<Result<Void>> businessError() {
        throw new BusinessException(1001, "业务断言异常触发");
    }

    /**
     * 系统未知异常模拟终结点 (强制脱敏且严禁泄漏堆栈)
     *
     * @return 异常结果
     */
    @GetMapping("/server-error")
    public Mono<Result<Void>> serverError() {
        throw new RuntimeException("底层未知数据库异常，禁止泄漏堆栈");
    }

    /**
     * Sa-Token 未登录异常统一转译模拟终结点 (HTTP 401 Result 契约)
     *
     * @return 异常结果
     */
    @GetMapping("/satoken/not-login")
    public Mono<Void> saTokenNotLogin() {
        return Mono.error(new cn.dev33.satoken.exception.NotLoginException(
                "未登录",
                cn.dev33.satoken.stp.StpUtil.TYPE,
                cn.dev33.satoken.exception.NotLoginException.NOT_TOKEN));
    }

    /**
     * Sa-Token 无权限异常统一转译模拟终结点 (HTTP 403 Result 契约)
     *
     * @return 异常结果
     */
    @GetMapping("/satoken/not-permission")
    public Mono<Void> saTokenNotPermission() {
        return Mono.error(new cn.dev33.satoken.exception.NotPermissionException("order:export:all"));
    }
}
