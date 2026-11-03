package io.github.atengk.cloud.sample.traffic.controller;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.traffic.model.OrderFulfillmentVO;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * 微服务全链路沙箱拓扑内部节点模拟控制器
 * <p>
 * 模拟电商履约三级拓扑：Order Service ➔ Inventory Service ➔ Logistics Service
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/traffic/topology")
public class TrafficTopologyMockProviderController {

    private static final Logger log = LoggerFactory.getLogger(TrafficTopologyMockProviderController.class);

    private static final ParameterizedTypeReference<Result<OrderFulfillmentVO>> VO_TYPE_REF =
            new ParameterizedTypeReference<>() {};

    @Autowired
    @Qualifier("loadBalancedRestTemplate")
    private RestTemplate restTemplate;

    /**
     * 订单中心业务处理入口 (调用下游库存中心)
     *
     * @param orderNo     订单编号
     * @param instanceId  负载均衡选中的实例 ID
     * @param instanceTag 负载均衡选中的实例标签
     * @return 订单履约链路轨迹
     */
    @GetMapping("/order-service/process")
    public Result<OrderFulfillmentVO> orderServiceProcess(
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestHeader(value = "X-Selected-Instance-Id", required = false) String instanceId,
            @RequestHeader(value = "X-Selected-Instance-Tag", required = false) String instanceTag) {
        String currentTag = TrafficContextHolder.getTag();
        log.info("[OrderService] 处理订单调度 [orderNo='{}', tag='{}', instanceId='{}']", orderNo, currentTag, instanceId);

        String effectiveOrderNo = (orderNo != null && !orderNo.isBlank()) ? orderNo : "ORD-20261103-0001";
        // 东西向调用下游库存中心
        OrderFulfillmentVO vo = invokeDownstream(
                "http://inventory-service/sample/cloud/traffic/topology/inventory-service/process?orderNo=" + effectiveOrderNo,
                currentTag,
                effectiveOrderNo
        );
        vo.prependVisitedNode("OrderService[inst=" + instanceId + ", tag=" + instanceTag + "]");
        return Result.success(vo);
    }

    /**
     * 库存中心业务处理入口 (模拟未发版服务，调用下游物流履约中心)
     *
     * @param orderNo     订单编号
     * @param instanceId  负载均衡选中的实例 ID
     * @param instanceTag 负载均衡选中的实例标签
     * @return 订单履约链路轨迹
     */
    @GetMapping("/inventory-service/process")
    public Result<OrderFulfillmentVO> inventoryServiceProcess(
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestHeader(value = "X-Selected-Instance-Id", required = false) String instanceId,
            @RequestHeader(value = "X-Selected-Instance-Tag", required = false) String instanceTag) {
        String currentTag = TrafficContextHolder.getTag();
        log.info("[InventoryService] 处理库存锁定 [orderNo='{}', tag='{}', instanceId='{}']", orderNo, currentTag, instanceId);

        String effectiveOrderNo = (orderNo != null && !orderNo.isBlank()) ? orderNo : "ORD-20261103-0001";
        // 东西向调用下游物流履约中心
        OrderFulfillmentVO vo = invokeDownstream(
                "http://logistics-service/sample/cloud/traffic/topology/logistics-service/process?orderNo=" + effectiveOrderNo,
                currentTag,
                effectiveOrderNo
        );
        String nodeDesc = (instanceId != null && !instanceId.isBlank())
                ? "inst=" + instanceId + ", tag=" + instanceTag
                : "feign-direct";
        vo.prependVisitedNode("InventoryService[" + nodeDesc + "]");
        return Result.success(vo);
    }

    /**
     * 物流履约中心业务终点入口 (叶子节点，返回终态)
     *
     * @param orderNo     订单编号
     * @param instanceId  负载均衡选中的实例 ID
     * @param instanceTag 负载均衡选中的实例标签
     * @return 订单履约链路轨迹
     */
    @GetMapping("/logistics-service/process")
    public Result<OrderFulfillmentVO> logisticsServiceProcess(
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestHeader(value = "X-Selected-Instance-Id", required = false) String instanceId,
            @RequestHeader(value = "X-Selected-Instance-Tag", required = false) String instanceTag) {
        String currentTag = TrafficContextHolder.getTag();
        log.info("[LogisticsService] 处理物流履约 [orderNo='{}', tag='{}', instanceId='{}']", orderNo, currentTag, instanceId);

        String effectiveOrderNo = (orderNo != null && !orderNo.isBlank()) ? orderNo : "ORD-20261103-0001";
        OrderFulfillmentVO vo = new OrderFulfillmentVO(
                currentTag,
                UserContextHolder.getUserId(),
                TenantContextHolder.getTenantId(),
                effectiveOrderNo
        );
        vo.setDeliveryStatus("FULFILLED");
        vo.addVisitedNode("LogisticsService[inst=" + instanceId + ", tag=" + instanceTag + "]");
        return Result.success(vo);
    }

    /**
     * 统一调用下游微服务并解包 Result<OrderFulfillmentVO>
     */
    private OrderFulfillmentVO invokeDownstream(String url, String currentTag, String orderNo) {
        ResponseEntity<Result<OrderFulfillmentVO>> response = restTemplate.exchange(url, HttpMethod.GET, null, VO_TYPE_REF);
        Result<OrderFulfillmentVO> body = response.getBody();
        if (body != null && body.getData() != null) {
            return body.getData();
        }
        OrderFulfillmentVO fallbackVO = new OrderFulfillmentVO(
                currentTag,
                UserContextHolder.getUserId(),
                TenantContextHolder.getTenantId(),
                orderNo
        );
        fallbackVO.setDeliveryStatus("DOWNSTREAM_EMPTY");
        return fallbackVO;
    }
}
