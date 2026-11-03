package io.github.atengk.cloud.sample.dubbo.api;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.dubbo.model.BillingStreamItemVO;
import io.github.atengk.cloud.sample.dubbo.model.BillingStreamQuery;
import io.github.atengk.cloud.sample.dubbo.model.TradeBillingRequest;
import io.github.atengk.cloud.sample.dubbo.model.TradeBillingResult;
import jakarta.validation.Valid;
import org.apache.dubbo.common.stream.StreamObserver;

import java.util.Map;

/**
 * 高频交易撮合与结算 Dubbo RPC 契约接口
 *
 * @author Ateng
 * @since 2026-11-03
 */
public interface TradeBillingDubboService {

    /**
     * 单笔同步结算（原生强类型 POJO 契约）
     *
     * @param request 交易结算入参
     * @return 结算结果详情
     */
    TradeBillingResult settlePojo(@Valid TradeBillingRequest request);

    /**
     * 单笔包装结算（Result 强契约返回，由消费端 DubboContractAssertFilter 自动断言成功状态）
     *
     * @param request 交易结算入参
     * @return 统一 Result 契约
     */
    Result<TradeBillingResult> settleResult(@Valid TradeBillingRequest request);

    /**
     * 模拟结算失败（返回 Result.fail，触发消费端自动断言并转换为 BusinessException）
     *
     * @param request 交易结算入参
     * @return 统一 Result 契约
     */
    Result<TradeBillingResult> settleFailedResult(@Valid TradeBillingRequest request);

    /**
     * Triple 原生 Server Stream 服务端流式推送对账明细
     *
     * @param query 查询参数
     * @param responseObserver 响应流观察者
     */
    void streamBillings(BillingStreamQuery query, StreamObserver<BillingStreamItemVO> responseObserver);

    /**
     * 探查服务端当前线程绑定的四维上下文、Seata XID 与流量染色标签快照
     *
     * @return 服务端线程上下文快照
     */
    Map<String, String> getProviderContext();
}
