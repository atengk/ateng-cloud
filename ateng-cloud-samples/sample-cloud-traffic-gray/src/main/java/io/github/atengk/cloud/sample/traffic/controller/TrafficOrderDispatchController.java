package io.github.atengk.cloud.sample.traffic.controller;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.traffic.client.InventoryFeignClient;
import io.github.atengk.cloud.sample.traffic.model.OrderFulfillmentVO;
import io.github.atengk.cloud.sample.traffic.support.TrafficDownstreamClientSupport;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
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

    private static final String ORDER_SERVICE_URI_TEMPLATE =
            "http://order-service/sample/cloud/traffic/topology/order-service/process?orderNo={orderNo}";

    @Autowired
    @Qualifier("loadBalancedRestTemplate")
    private RestTemplate restTemplate;

    @Autowired
    @Qualifier("loadBalancedRestClient")
    private RestClient restClient;

    @Autowired(required = false)
    private InventoryFeignClient inventoryFeignClient;

    /**
     * 基础订单履约调度入口 (模拟公网网关接入，自动提取 Header 或 Query 流量标签并调度三级链路)
     *
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/dispatch")
    public Result<OrderFulfillmentVO> dispatchOrder(
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        String effectiveOrderNo = TrafficDownstreamClientSupport.normalizeOrderNo(orderNo, "ORD-20261103-9001");
        String currentTag = TrafficContextHolder.getTag();

        log.info("[OrderDispatch] 收到订单调度请求 [orderNo='{}', tag='{}', userId='{}', tenantId='{}']",
                effectiveOrderNo, currentTag, UserContextHolder.getUserId(), TenantContextHolder.getTenantId());

        OrderFulfillmentVO vo = TrafficDownstreamClientSupport.invokeTopologyService(
                restTemplate, "order-service", currentTag, effectiveOrderNo);
        vo.prependVisitedNode("Gateway[tag=" + (currentTag != null ? currentTag : "none") + "]");
        return Result.success(vo);
    }

    /**
     * 基于 MurmurHash3 的一致性哈希单用户会话粘性切流演练
     *
     * @param userId  业务用户 ID (用作一致性哈希种子)
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/canary-hash")
    public Result<OrderFulfillmentVO> canaryHashDispatch(
            @RequestParam("userId") String userId,
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        try {
            UserContextHolder.setUserId(userId);
            return dispatchOrder(orderNo);
        } finally {
            UserContextHolder.clear();
        }
    }

    /**
     * 多金丝雀版本互斥分桶放量切流演练 (基于 percentageRange [min, max) 互斥分流)
     *
     * @param userId  业务用户 ID (用作区间分桶判定)
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/multi-canary")
    public Result<OrderFulfillmentVO> multiCanaryDispatch(
            @RequestParam("userId") String userId,
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        try {
            UserContextHolder.setUserId(userId);
            return dispatchOrder(orderNo);
        } finally {
            UserContextHolder.clear();
        }
    }

    /**
     * 声明式 Feign 跨微服务灰度调度入口 (演示 TrafficFeignRequestInterceptor 自动出站透传)
     *
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/feign-dispatch")
    public Result<OrderFulfillmentVO> feignDispatch(
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        String effectiveOrderNo = TrafficDownstreamClientSupport.normalizeOrderNo(orderNo, "ORD-20261103-FEIGN");
        String currentTag = TrafficContextHolder.getTag();

        log.info("[FeignDispatch] 收到声明式 Feign 订单调度请求 [orderNo='{}', tag='{}', userId='{}', tenantId='{}']",
                effectiveOrderNo, currentTag, UserContextHolder.getUserId(), TenantContextHolder.getTenantId());

        OrderFulfillmentVO vo;
        if (inventoryFeignClient != null) {
            Result<OrderFulfillmentVO> result = inventoryFeignClient.processOrder(effectiveOrderNo, null, null);
            vo = (result != null && result.getData() != null)
                    ? result.getData()
                    : TrafficDownstreamClientSupport.buildEmptyFulfillmentVO(currentTag, effectiveOrderNo);
        } else {
            vo = TrafficDownstreamClientSupport.buildEmptyFulfillmentVO(currentTag, effectiveOrderNo);
        }
        vo.prependVisitedNode("FeignGateway[tag=" + (currentTag != null ? currentTag : "none") + "]");
        if (vo.getOrderNo() == null) {
            vo.setOrderNo(effectiveOrderNo);
        }
        return Result.success(vo);
    }

    /**
     * Spring 6 RestClient 东西向灰度调度入口 (演示 TrafficClientHttpRequestInterceptor 自动出站透传)
     *
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/restclient-dispatch")
    public Result<OrderFulfillmentVO> restClientDispatch(
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        String effectiveOrderNo = TrafficDownstreamClientSupport.normalizeOrderNo(orderNo, "ORD-20261113-RC");
        String currentTag = TrafficContextHolder.getTag();

        log.info("[RestClientDispatch] 收到 Spring 6 RestClient 订单调度请求 [orderNo='{}', tag='{}', userId='{}', tenantId='{}']",
                effectiveOrderNo, currentTag, UserContextHolder.getUserId(), TenantContextHolder.getTenantId());

        Result<OrderFulfillmentVO> body = restClient.get()
                .uri(ORDER_SERVICE_URI_TEMPLATE, effectiveOrderNo)
                .retrieve()
                .body(TrafficDownstreamClientSupport.ORDER_FULFILLMENT_TYPE);
        OrderFulfillmentVO vo = (body != null && body.getData() != null)
                ? body.getData()
                : TrafficDownstreamClientSupport.buildEmptyFulfillmentVO(currentTag, effectiveOrderNo);
        vo.prependVisitedNode("RestClientGateway[tag=" + (currentTag != null ? currentTag : "none") + "]");
        return Result.success(vo);
    }

    /**
     * NOT_EXACT / NOT_IN 反向排除谓词动态切流演练入口
     *
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/exclusion-predicate")
    public Result<OrderFulfillmentVO> exclusionPredicateDispatch(
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        String effectiveOrderNo = TrafficDownstreamClientSupport.normalizeOrderNo(orderNo, "ORD-20261113-EXCL");
        String currentTag = TrafficContextHolder.getTag();

        log.info("[ExclusionDispatch] 收到反向排除谓词切流请求 [orderNo='{}', tag='{}']",
                effectiveOrderNo, currentTag);

        OrderFulfillmentVO vo = TrafficDownstreamClientSupport.invokeTopologyService(
                restTemplate, "order-service", currentTag, effectiveOrderNo);
        vo.prependVisitedNode("ExclusionEntry[tag=" + (currentTag != null ? currentTag : "none") + "]");
        return Result.success(vo);
    }
}
