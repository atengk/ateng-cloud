package io.github.atengk.cloud.sample.traffic.support;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.traffic.model.OrderFulfillmentVO;
import io.github.atengk.cloud.traffic.code.TrafficResultCode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 流量灰度演练控制器公共下游调用与参数防御辅助组件
 *
 * @author Ateng
 * @since 2026-11-13
 */
public final class TrafficDownstreamClientSupport {

    /**
     * 下游订单履约轨迹统一响应类型声明
     */
    public static final ParameterizedTypeReference<Result<OrderFulfillmentVO>> ORDER_FULFILLMENT_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final Pattern SAFE_ORDER_NO_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final Set<String> ALLOWED_TOPOLOGY_SERVICES =
            Set.of("order-service", "inventory-service", "logistics-service");

    private static final String TOPOLOGY_URL_TEMPLATE =
            "http://{service}/sample/cloud/traffic/topology/{service}/process?orderNo={orderNo}";

    private TrafficDownstreamClientSupport() {
    }

    /**
     * 校验并规范化订单编号，防止非法字符进入下游 URI 参数
     *
     * @param orderNo        入参订单号
     * @param defaultOrderNo 为空时的默认订单号
     * @return 安全合法的订单号
     */
    public static String normalizeOrderNo(String orderNo, String defaultOrderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return defaultOrderNo;
        }
        String trimmed = orderNo.trim();
        if (!SAFE_ORDER_NO_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessException(TrafficResultCode.TRAFFIC_ROUTING_ERROR,
                    "非法的订单编号格式: " + orderNo);
        }
        return trimmed;
    }

    /**
     * 校验并返回受信任的拓扑微服务名称
     *
     * @param targetService 目标服务名
     * @return 小写规范化服务名
     */
    public static String validateTopologyService(String targetService) {
        String normalizedService = targetService != null ? targetService.trim().toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_TOPOLOGY_SERVICES.contains(normalizedService)) {
            throw new BusinessException(TrafficResultCode.TRAFFIC_ROUTING_ERROR,
                    "非法的演练目标服务名: " + targetService);
        }
        return normalizedService;
    }

    /**
     * 通过负载均衡 RestTemplate 调用指定拓扑节点并返回履约轨迹
     *
     * @param restTemplate  负载均衡 RestTemplate
     * @param targetService 目标服务名
     * @param currentTag    当前流量染色标签
     * @param orderNo       已校验订单编号
     * @return 订单履约轨迹 VO
     */
    public static OrderFulfillmentVO invokeTopologyService(RestTemplate restTemplate,
                                                             String targetService,
                                                             String currentTag,
                                                             String orderNo) {
        String safeService = validateTopologyService(targetService);
        ResponseEntity<Result<OrderFulfillmentVO>> response = restTemplate.exchange(
                TOPOLOGY_URL_TEMPLATE,
                HttpMethod.GET,
                null,
                ORDER_FULFILLMENT_TYPE,
                safeService,
                safeService,
                orderNo
        );
        Result<OrderFulfillmentVO> body = response.getBody();
        if (body != null && body.getData() != null) {
            return body.getData();
        }
        return buildEmptyFulfillmentVO(currentTag, orderNo);
    }

    /**
     * 构建下游空响应兜底履约轨迹 VO
     *
     * @param currentTag 当前流量染色标签
     * @param orderNo    订单编号
     * @return 兜底 VO
     */
    public static OrderFulfillmentVO buildEmptyFulfillmentVO(String currentTag, String orderNo) {
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
