package io.github.atengk.cloud.sample.dubbo.service;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.dubbo.filter.DubboContextPropagationFilter;
import io.github.atengk.cloud.dubbo.support.DubboSeataPropagationSupport;
import io.github.atengk.cloud.dubbo.support.DubboTrafficPropagationSupport;
import io.github.atengk.cloud.sample.dubbo.api.TradeBillingDubboService;
import io.github.atengk.cloud.sample.dubbo.model.BillingStreamItemVO;
import io.github.atengk.cloud.sample.dubbo.model.BillingStreamQuery;
import io.github.atengk.cloud.sample.dubbo.model.TradeBillingRequest;
import io.github.atengk.cloud.sample.dubbo.model.TradeBillingResult;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 高频交易撮合与结算 Dubbo RPC 服务端实现
 *
 * @author Ateng
 * @since 2026-11-03
 */
@DubboService
public class TradeBillingDubboServiceImpl implements TradeBillingDubboService {

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("88888.88");

    @Override
    public TradeBillingResult settlePojo(TradeBillingRequest request) {
        BigDecimal balance = INITIAL_BALANCE.subtract(request.getAmount());
        String traceId = MDC.get("traceId");
        String billingNo = "BILL_" + System.currentTimeMillis();

        return new TradeBillingResult(
                billingNo,
                request.getOrderNo(),
                request.getAccountId(),
                request.getAmount(),
                balance,
                "SUCCESS",
                "node-trade-cluster-01",
                traceId,
                LocalDateTime.now()
        );
    }

    @Override
    public Result<TradeBillingResult> settleResult(TradeBillingRequest request) {
        TradeBillingResult result = settlePojo(request);
        return Result.success(result);
    }

    @Override
    public Result<TradeBillingResult> settleFailedResult(TradeBillingRequest request) {
        return Result.fail(4002, "账户结算余额不足，业务状态校验未通过");
    }

    @Override
    public void streamBillings(BillingStreamQuery query, StreamObserver<BillingStreamItemVO> responseObserver) {
        int count = query.getLimit() != null && query.getLimit() > 0 ? query.getLimit() : 5;
        int batchSize = query.getBatchSize() != null && query.getBatchSize() > 0 ? query.getBatchSize() : 5;

        for (int i = 1; i <= count; i++) {
            int batchIndex = ((i - 1) / batchSize) + 1;
            BillingStreamItemVO item = new BillingStreamItemVO(
                    "STREAM_BILL_" + System.currentTimeMillis() + "_" + i,
                    "ORD_STREAM_" + i,
                    new BigDecimal("100.00").multiply(BigDecimal.valueOf(i)),
                    "SETTLED",
                    batchIndex,
                    LocalDateTime.now()
            );
            responseObserver.onNext(item);
        }
        responseObserver.onCompleted();
    }

    @Override
    public Map<String, String> getProviderContext() {
        Map<String, String> context = new HashMap<>();
        context.put("traceId", MDC.get("traceId"));
        context.put("userId", UserContextHolder.getUserId());
        context.put("tenantId", TenantContextHolder.getTenantId());
        context.put("ignoreTenant", String.valueOf(TenantContextHolder.isIgnoreTenant()));
        context.put("trafficTag", MDC.get(DubboContextPropagationFilter.TRAFFIC_TAG));
        context.put("xid", DubboSeataPropagationSupport.getXID());

        var trafficSnapshot = DubboTrafficPropagationSupport.getSnapshot();
        if (trafficSnapshot != null) {
            if (trafficSnapshot.tag() != null) {
                context.put("trafficTag", trafficSnapshot.tag());
            }
            if (trafficSnapshot.laneName() != null) {
                context.put("trafficLane", trafficSnapshot.laneName());
            }
            if (trafficSnapshot.matchedRule() != null) {
                context.put("trafficRule", trafficSnapshot.matchedRule());
            }
        }
        return context;
    }
}
