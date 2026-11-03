package io.github.atengk.cloud.sample.traffic.client;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.traffic.model.OrderFulfillmentVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 远程库存微服务声明式 Feign 客户端
 * <p>
 * 用于演示微服务东西向跨进程调用时，TrafficFeignRequestInterceptor 自动出站透传 X-Traffic-Tag。
 *
 * @author Ateng
 * @since 2026-11-03
 */
@FeignClient(name = "inventory-service", path = "/sample/cloud/traffic/topology/inventory-service")
public interface InventoryFeignClient {

    /**
     * 处理库存锁定与下游拓扑调度
     *
     * @param orderNo     订单编号
     * @param instanceId  实例 ID 占位
     * @param instanceTag 实例 Tag 占位
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/process")
    Result<OrderFulfillmentVO> processOrder(
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestHeader(value = "X-Selected-Instance-Id", required = false) String instanceId,
            @RequestHeader(value = "X-Selected-Instance-Tag", required = false) String instanceTag);
}
