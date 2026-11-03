package io.github.atengk.cloud.sample.seata.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.seata.service.OrderService;
import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活跃事务 Sentinel 熔断降级防卫演示控制器
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Validated
@RestController
@RequestMapping("/sample/cloud/seata/fallback")
public class SeataFallbackDefenseController {

    private static final Logger log = LoggerFactory.getLogger(SeataFallbackDefenseController.class);

    private final OrderService orderService;

    public SeataFallbackDefenseController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 演示在活跃分布式事务下，下游触发熔断降级时强制抛出 5104 阻断假提交以保证全局回滚
     *
     * @param userId        用户 ID
     * @param commodityCode 商品编码
     * @param count         订购数量
     * @return 响应结果（预期被全局异常拦截器转译为 5104 错误码）
     */
    @PostMapping("/with-active-tx")
    public Result<String> withActiveTransaction(
            @RequestParam(value = "userId", defaultValue = "user_fallback_001") String userId,
            @RequestParam(value = "commodityCode", defaultValue = "product_fallback_001") String commodityCode,
            @RequestParam(value = "count", defaultValue = "2") Integer count) {
        log.info("模拟在活跃全局事务下发起带熔断降级的下游调用: 用户 [{}], 商品 [{}]", userId, commodityCode);

        boolean manuallyBound = false;
        if (!RootContext.inGlobalTransaction()) {
            RootContext.bind("127.0.0.1:8091:fallback-test-tx-" + System.currentTimeMillis());
            manuallyBound = true;
        }

        try {
            orderService.createOrderWithDownstream(userId, commodityCode, count, () -> {
                throw new RuntimeException("Sentinel FlowException: 下游外部服务限流熔断");
            });
            return Result.success("执行完成（非预期，应被 5104 拦截）");
        } finally {
            if (manuallyBound && RootContext.inGlobalTransaction()) {
                RootContext.unbind();
            }
        }
    }

    /**
     * 演示在无分布式事务的普通调用下，下游触发熔断降级时平滑放行返回保底数据
     *
     * @param userId        用户 ID
     * @param commodityCode 商品编码
     * @param count         订购数量
     * @return 业务降级保底响应
     */
    @PostMapping("/without-tx")
    public Result<String> withoutTransaction(
            @RequestParam(value = "userId", defaultValue = "user_fallback_002") String userId,
            @RequestParam(value = "commodityCode", defaultValue = "product_fallback_002") String commodityCode,
            @RequestParam(value = "count", defaultValue = "2") Integer count) {
        log.info("模拟在无全局事务下发起带熔断降级的下游调用: 用户 [{}], 商品 [{}]", userId, commodityCode);

        // 确保清除可能存在的事务上下文
        if (RootContext.inGlobalTransaction()) {
            RootContext.unbind();
        }

        Long orderId = orderService.createOrderWithDownstream(userId, commodityCode, count, () -> {
            throw new RuntimeException("Sentinel FlowException: 下游外部服务限流降级");
        });

        return Result.success("当前无活跃全局分布式事务，下游调用平滑降级执行成功，订单暂存 ID: " + orderId);
    }
}
