package io.github.atengk.cloud.sample.gateway.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.sample.gateway.model.OrderRouteDispatchVO;
import io.github.atengk.cloud.sample.gateway.model.RouteDefinitionVO;
import io.github.atengk.cloud.sample.gateway.model.RouteRefreshResultVO;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 响应式网关动态路由管理与全链路金丝雀灰度分流控制器
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/gateway/route")
public class GatewayRouteTrafficController {

    private final RouteDefinitionLocator routeDefinitionLocator;
    private final ApplicationEventPublisher applicationEventPublisher;

    public GatewayRouteTrafficController(RouteDefinitionLocator routeDefinitionLocator,
                                         ApplicationEventPublisher applicationEventPublisher) {
        this.routeDefinitionLocator = routeDefinitionLocator;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 查询当前网关生效的全量动态路由定义列表
     *
     * @return 路由定义契约视图列表
     */
    @GetMapping("/definitions")
    public Mono<Result<List<RouteDefinitionVO>>> definitions() {
        return routeDefinitionLocator.getRouteDefinitions()
                .map(def -> new RouteDefinitionVO(
                        def.getId(),
                        def.getUri() != null ? def.getUri().toString() : "-",
                        def.getPredicates().stream().map(Object::toString).toList(),
                        def.getFilters().stream().map(Object::toString).toList(),
                        def.getOrder()
                ))
                .collectList()
                .map(Result::success);
    }

    /**
     * 触发网关动态路由刷新事件并同步最新内存路由快照
     *
     * @return 刷新执行结果视图
     */
    @PostMapping("/refresh")
    public Mono<Result<RouteRefreshResultVO>> refresh() {
        applicationEventPublisher.publishEvent(new RefreshRoutesEvent(this));
        return routeDefinitionLocator.getRouteDefinitions()
                .count()
                .map(count -> Result.success(new RouteRefreshResultVO(
                        "SUCCESS",
                        count.intValue(),
                        "HEALTHY",
                        "动态路由刷新事件广播完成，内存路由表已无感原子重构"
                )));
    }

    /**
     * 电商订单中心微服务路由分发与金丝雀灰度标签匹配
     *
     * @param orderId 订单唯一标识
     * @param request 响应式 HTTP 入站请求
     * @return 订单路由调度决策视图
     */
    @GetMapping("/order-dispatch")
    public Mono<Result<OrderRouteDispatchVO>> orderDispatch(
            @RequestParam(defaultValue = "ORDER-20261103001") String orderId,
            ServerHttpRequest request) {
        // 1. 优先提取权威染色请求头 X-Traffic-Tag，兜底提取 URL 查询参数 traffic_tag
        String tag = request.getHeaders().getFirst(GatewayConstants.X_TRAFFIC_TAG);
        if (!StringUtils.hasText(tag)) {
            tag = request.getQueryParams().getFirst(GatewayConstants.QUERY_TRAFFIC_TAG);
        }
        if (!StringUtils.hasText(tag)) {
            tag = "baseline";
        }

        // 2. 提取响应式全链路追踪 traceId 与客户端 IP
        String traceId = request.getHeaders().getFirst(GatewayConstants.X_TRACE_ID);
        String clientIp = request.getHeaders().getFirst(GatewayConstants.X_REAL_IP);
        if (!StringUtils.hasText(clientIp)) {
            clientIp = request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : "127.0.0.1";
        }

        boolean isCanary = tag.contains("canary") || tag.contains("v2") || tag.contains("vip");
        String status = isCanary ? "DISPATCHED_TO_CANARY" : "DISPATCHED_TO_BASELINE";

        OrderRouteDispatchVO vo = new OrderRouteDispatchVO(
                orderId,
                "order-service",
                tag,
                "zone-beijing-az1",
                traceId,
                clientIp,
                status,
                false
        );
        return Mono.just(Result.success(vo));
    }

    /**
     * 演示金丝雀灰度实例全下线时，网关自动平滑降级调度至基线默认实例的行为
     *
     * @param orderId 订单唯一标识
     * @param request 响应式 HTTP 入站请求
     * @return 降级回退调度结果视图
     */
    @GetMapping("/canary-fallback")
    public Mono<Result<OrderRouteDispatchVO>> canaryFallback(
            @RequestParam(defaultValue = "ORDER-20261103002") String orderId,
            ServerHttpRequest request) {
        String tag = request.getHeaders().getFirst(GatewayConstants.X_TRAFFIC_TAG);
        String traceId = request.getHeaders().getFirst(GatewayConstants.X_TRACE_ID);
        String clientIp = request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : "127.0.0.1";

        OrderRouteDispatchVO vo = new OrderRouteDispatchVO(
                orderId,
                "order-service",
                tag != null ? tag : "canary-v2",
                "zone-beijing-az1",
                traceId,
                clientIp,
                "FALLBACK_TO_BASELINE",
                true
        );
        return Mono.just(Result.success(vo));
    }
}
