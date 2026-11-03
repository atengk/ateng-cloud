package io.github.atengk.cloud.sample.traffic.controller;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.traffic.model.OrderFulfillmentVO;
import io.github.atengk.cloud.traffic.annotation.TrafficTag;
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
 * 灰度治理策略管控、安全防线与容灾兜底控制器
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/traffic/policy")
public class TrafficCanaryPolicyController {

    private static final Logger log = LoggerFactory.getLogger(TrafficCanaryPolicyController.class);

    private static final ParameterizedTypeReference<Result<OrderFulfillmentVO>> VO_TYPE_REF =
            new ParameterizedTypeReference<>() {};

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
        String currentTag = TrafficContextHolder.getTag();
        log.info("[PolicyController] 声明式入口执行 [@TrafficTag('v2'), currentTag='{}']", currentTag);

        String effectiveOrderNo = (orderNo != null && !orderNo.isBlank()) ? orderNo : "ORD-20261103-DECL";
        OrderFulfillmentVO vo = invokeDownstream(
                "http://order-service/sample/cloud/traffic/topology/order-service/process?orderNo=" + effectiveOrderNo,
                currentTag,
                effectiveOrderNo
        );
        vo.prependVisitedNode("DeclarativeEntry[tag=" + (currentTag != null ? currentTag : "none") + "]");
        return Result.success(vo);
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
        String currentTag = TrafficContextHolder.getTag();
        log.info("[PolicyController] 白名单校验入口执行 [currentTag='{}']", currentTag);

        String effectiveOrderNo = (orderNo != null && !orderNo.isBlank()) ? orderNo : "ORD-20261103-WHITELIST";
        OrderFulfillmentVO vo = invokeDownstream(
                "http://order-service/sample/cloud/traffic/topology/order-service/process?orderNo=" + effectiveOrderNo,
                currentTag,
                effectiveOrderNo
        );
        vo.prependVisitedNode("WhitelistEntry[tag=" + (currentTag != null ? currentTag : "none") + "]");
        return Result.success(vo);
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
        String currentTag = TrafficContextHolder.getTag();
        log.info("[PolicyController] 严格隔离入口执行 [currentTag='{}']", currentTag);

        String effectiveOrderNo = (orderNo != null && !orderNo.isBlank()) ? orderNo : "ORD-20261103-STRICT";
        OrderFulfillmentVO vo = invokeDownstream(
                "http://order-service/sample/cloud/traffic/topology/order-service/process?orderNo=" + effectiveOrderNo,
                currentTag,
                effectiveOrderNo
        );
        vo.prependVisitedNode("StrictLaneEntry[tag=" + (currentTag != null ? currentTag : "none") + "]");
        return Result.success(vo);
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
        String currentTag = TrafficContextHolder.getTag();
        log.info("[PolicyController] 弹性降级入口执行 [currentTag='{}']", currentTag);

        String effectiveOrderNo = (orderNo != null && !orderNo.isBlank()) ? orderNo : "ORD-20261103-FALLBACK";
        OrderFulfillmentVO vo = invokeDownstream(
                "http://order-service/sample/cloud/traffic/topology/order-service/process?orderNo=" + effectiveOrderNo,
                currentTag,
                effectiveOrderNo
        );
        vo.prependVisitedNode("FallbackEntry[tag=" + (currentTag != null ? currentTag : "none") + "]");
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
