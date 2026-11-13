package io.github.atengk.cloud.sample.traffic.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.traffic.model.OrderFulfillmentVO;
import io.github.atengk.cloud.sample.traffic.support.TrafficDownstreamClientSupport;
import io.github.atengk.cloud.traffic.annotation.TrafficTag;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * 灰度治理策略管控、安全防线与容灾兜底控制器
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/traffic/policy")
public class TrafficCanaryPolicyController {

    private static final Logger log = LoggerFactory.getLogger(TrafficCanaryPolicyController.class);

    @Autowired
    @Qualifier("loadBalancedRestTemplate")
    private RestTemplate restTemplate;

    /**
     * 声明式方法级切面调度入口 (使用 @TrafficTag("v2") 声明式切流并自动出站透传，出口还原)
     *
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/declarative-tag")
    @TrafficTag("v2")
    public Result<OrderFulfillmentVO> declarativeTagDispatch(
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        return dispatchPolicyOrder(orderNo, "ORD-20261103-DECL", "DeclarativeEntry");
    }

    /**
     * 合法流量标签白名单防伪校验演练 (演示 STRIP 剥离降级基线 vs REJECT 4212 异常拦截)
     *
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/whitelist-check")
    public Result<OrderFulfillmentVO> whitelistCheck(
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        return dispatchPolicyOrder(orderNo, "ORD-20261103-WHITELIST", "WhitelistEntry");
    }

    /**
     * 金融级严格泳道强隔离演练 (未命中灰度实例时 Fail-Fast 阻断抛出 4210 异常)
     *
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/strict-lane")
    public Result<OrderFulfillmentVO> strictLaneDispatch(
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        return dispatchPolicyOrder(orderNo, "ORD-20261103-STRICT", "StrictLaneEntry");
    }

    /**
     * 灰度节点全下线弹性平滑降级演练 (灰度下线平滑回退基线，零 503 报警)
     *
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/resilient-fallback")
    public Result<OrderFulfillmentVO> resilientFallbackDispatch(
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        return dispatchPolicyOrder(orderNo, "ORD-20261103-FALLBACK", "FallbackEntry");
    }

    /**
     * 按下游目标服务名细粒度激活严格泳道隔离演练 (strict-lane-services)
     *
     * @param targetService 演练目标服务名 (order-service / inventory-service / logistics-service)
     * @param orderNo       订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/strict-lane-service")
    public Result<OrderFulfillmentVO> strictLaneByServiceDispatch(
            @RequestParam(value = "targetService", defaultValue = "order-service") String targetService,
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        String normalizedService = TrafficDownstreamClientSupport.validateTopologyService(targetService);
        String effectiveOrderNo = TrafficDownstreamClientSupport.normalizeOrderNo(orderNo, "ORD-20261113-STRICT-SVC");
        String currentTag = TrafficContextHolder.getTag();
        log.info("[PolicyController] 服务级严格隔离入口执行 [targetService='{}', currentTag='{}']",
                normalizedService, currentTag);

        OrderFulfillmentVO vo = TrafficDownstreamClientSupport.invokeTopologyService(
                restTemplate, normalizedService, currentTag, effectiveOrderNo);
        vo.prependVisitedNode("StrictServiceEntry[service=" + normalizedService
                + ", tag=" + (currentTag != null ? currentTag : "none") + "]");
        return Result.success(vo);
    }

    /**
     * 按流量染色标签细粒度激活严格泳道隔离演练 (strict-lane-tags)
     *
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/strict-lane-tag")
    public Result<OrderFulfillmentVO> strictLaneByTagDispatch(
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        return dispatchPolicyOrder(orderNo, "ORD-20261113-STRICT-TAG", "StrictTagEntry");
    }

    /**
     * 三级规则消解演练入口 (Nacos 应用专属规则 -> Nacos 全局公共规则 -> 本地静态规则)
     *
     * @param orderNo 订单编号 (可选)
     * @return 订单履约全链路轨迹
     */
    @GetMapping("/hierarchical-rules")
    public Result<OrderFulfillmentVO> hierarchicalRulesDispatch(
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        return dispatchPolicyOrder(orderNo, "ORD-20261113-HIER", "HierarchicalRuleEntry");
    }

    private Result<OrderFulfillmentVO> dispatchPolicyOrder(String orderNo, String defaultOrderNo, String entryNodePrefix) {
        String effectiveOrderNo = TrafficDownstreamClientSupport.normalizeOrderNo(orderNo, defaultOrderNo);
        String currentTag = TrafficContextHolder.getTag();
        log.info("[PolicyController] {} 执行 [orderNo='{}', currentTag='{}']",
                entryNodePrefix, effectiveOrderNo, currentTag);

        OrderFulfillmentVO vo = TrafficDownstreamClientSupport.invokeTopologyService(
                restTemplate, "order-service", currentTag, effectiveOrderNo);
        vo.prependVisitedNode(entryNodePrefix + "[tag=" + (currentTag != null ? currentTag : "none") + "]");
        return Result.success(vo);
    }
}
