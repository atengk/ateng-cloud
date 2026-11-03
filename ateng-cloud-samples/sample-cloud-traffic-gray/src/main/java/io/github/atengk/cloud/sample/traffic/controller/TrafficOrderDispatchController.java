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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * 微服务全链路订单调度与灰度切流控制器
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/traffic/order")
public class TrafficOrderDispatchController {

    private static final Logger log = LoggerFactory.getLogger(TrafficOrderDispatchController.class);

    private static final ParameterizedTypeReference<Result<OrderFulfillmentVO>> VO_TYPE_REF =
            new ParameterizedTypeReference<>() {};

    @Autowired
    @Qualifier("loadBalancedRestTemplate")
    private RestTemplate restTemplate;

    /**
     * 基础订单履约调度入口 (模拟公网网关接入，自动提取 Header 或 Query 流量标签并调度三级链路)
     *
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/dispatch")
    public Result<OrderFulfillmentVO> dispatchOrder(
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        String currentTag = TrafficContextHolder.getTag();
        String currentUserId = UserContextHolder.getUserId();
        String currentTenantId = TenantContextHolder.getTenantId();

        log.info("[OrderDispatch] 收到订单调度请求 [orderNo='{}', tag='{}', userId='{}', tenantId='{}']",
                orderNo, currentTag, currentUserId, currentTenantId);

        String effectiveOrderNo = (orderNo != null && !orderNo.isBlank()) ? orderNo : "ORD-20261103-9001";
        // 调度下游 OrderService 节点
        OrderFulfillmentVO vo = invokeDownstream(
                "http://order-service/sample/cloud/traffic/topology/order-service/process?orderNo=" + effectiveOrderNo,
                currentTag,
                effectiveOrderNo
        );
        vo.prependVisitedNode("Gateway[tag=" + (currentTag != null ? currentTag : "none") + "]");
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
