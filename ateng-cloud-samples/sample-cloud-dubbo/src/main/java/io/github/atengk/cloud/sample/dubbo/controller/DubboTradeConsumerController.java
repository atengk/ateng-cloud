package io.github.atengk.cloud.sample.dubbo.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.dubbo.filter.DubboContextPropagationFilter;
import io.github.atengk.cloud.sample.dubbo.api.TradeBillingDubboService;
import io.github.atengk.cloud.sample.dubbo.model.BillingStreamItemVO;
import io.github.atengk.cloud.sample.dubbo.model.BillingStreamQuery;
import io.github.atengk.cloud.sample.dubbo.model.TradeBillingRequest;
import io.github.atengk.cloud.sample.dubbo.model.TradeBillingResult;
import jakarta.validation.Valid;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 高频交易撮合与结算 Web 接入消费控制器
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/dubbo/trade")
public class DubboTradeConsumerController {

    @DubboReference(url = "${dubbo.consumer.direct-url:${dubbo.test.reference.url:tri://127.0.0.1:50051}}", check = false)
    private TradeBillingDubboService tradeBillingDubboService;

    /**
     * 单笔同步结算（原生强类型 POJO 契约）
     *
     * @param request 交易结算请求
     * @return 强类型 Result 包装的结算详情
     */
    @PostMapping("/settle/pojo")
    public Result<TradeBillingResult> settlePojo(@RequestBody @Valid TradeBillingRequest request) {
        TradeBillingResult result = tradeBillingDubboService.settlePojo(request);
        return Result.success(result);
    }

    /**
     * 单笔统一包装结算（Result 契约，自动断言防呆剥壳）
     *
     * @param request 交易结算请求
     * @return 强类型 Result 契约
     */
    @PostMapping("/settle/result")
    public Result<TradeBillingResult> settleResult(@RequestBody @Valid TradeBillingRequest request) {
        return tradeBillingDubboService.settleResult(request);
    }

    /**
     * 模拟结算失败（对端返回非 200 Result，触发 DubboContractAssertFilter 自动转译为 BusinessException）
     *
     * @param request 交易结算请求
     * @return 强类型 Result 契约
     */
    @PostMapping("/settle/failed")
    public Result<TradeBillingResult> settleFailedResult(@RequestBody @Valid TradeBillingRequest request) {
        return tradeBillingDubboService.settleFailedResult(request);
    }

    /**
     * Triple 原生 Server Stream 服务端流式拉取对账明细
     *
     * @param accountId 结算账户 ID
     * @param limit 拉取总条数
     * @return 汇聚后的对账单明细列表
     */
    @GetMapping("/stream/bills")
    public Result<List<BillingStreamItemVO>> streamBillings(
            @RequestParam(required = false, defaultValue = "10001") Long accountId,
            @RequestParam(required = false, defaultValue = "5") Integer limit) {

        List<BillingStreamItemVO> items = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        BillingStreamQuery query = new BillingStreamQuery(accountId, 5, limit);
        tradeBillingDubboService.streamBillings(query, new StreamObserver<BillingStreamItemVO>() {
            @Override
            public void onNext(BillingStreamItemVO data) {
                items.add(data);
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            if (!completed) {
                throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE, "流式对账单拉取超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "流式对账单处理线程中断");
        }

        if (errorRef.get() != null) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "流式对账单推送失败: " + errorRef.get().getMessage());
        }

        return Result.success(items);
    }

    /**
     * 探查跨进程 RPC 四维上下文、Seata XID 与金丝雀灰度染色穿透
     *
     * @param trafficTag 灰度染色标签
     * @param userId 操作用户 ID
     * @param tenantId 租户 ID
     * @return 服务端线程上下文镜像快照
     */
    @GetMapping("/context/probe")
    public Result<Map<String, String>> probeContext(
            @RequestHeader(value = "X-Traffic-Tag", required = false) String trafficTag,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {

        if (userId != null && !userId.isBlank()) {
            UserContextHolder.setUserId(userId);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            TenantContextHolder.setTenantId(tenantId);
        }
        if (trafficTag != null && !trafficTag.isBlank()) {
            MDC.put(DubboContextPropagationFilter.TRAFFIC_TAG, trafficTag);
        }

        try {
            Map<String, String> providerContext = tradeBillingDubboService.getProviderContext();
            return Result.success(providerContext);
        } finally {
            UserContextHolder.clear();
            TenantContextHolder.clear();
            MDC.remove(DubboContextPropagationFilter.TRAFFIC_TAG);
        }
    }
}
