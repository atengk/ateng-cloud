package io.github.atengk.cloud.sample.gateway.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.gateway.model.OpenApiOrderSyncDTO;
import io.github.atengk.cloud.sample.gateway.model.OpenApiOrderSyncVO;
import io.github.atengk.cloud.sample.gateway.model.PayloadResultVO;
import io.github.atengk.cloud.sample.gateway.model.PerimeterSecurityVO;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 网关边缘安全防卫与开放接口签名控制器 (100% 响应式架构)
 *
 * <p>涵盖以下微服务边缘安全治理场景：
 * <ul>
 *     <li>开放平台商户订单同步 HMAC-SHA256 签名与防重放核验；</li>
 *     <li>开放平台订单查询签名校验；</li>
 *     <li>大报文载荷 2MB 限制防御与防爆；</li>
 *     <li>IP 黑白名单过滤与单 IP / CIDR 拦截；</li>
 *     <li>URI 目录穿越检测与规范化折叠；</li>
 *     <li>可信代理真实 IP 倒推解析与公网敏感标头物理剥离清洗。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/gateway/security")
public class GatewaySecurityGuardController {

    private static final long MAX_ALLOWED_BYTES = 2 * 1024 * 1024L;

    /**
     * 开放平台商户订单同步接口 (受 HMAC-SHA256 签名与 Nonce 防重放守卫保护)
     *
     * @param bodyMono 异步订单同步载荷
     * @return 同步处理结果强类型视图
     */
    @PostMapping("/open/merchant/sync-order")
    public Mono<Result<OpenApiOrderSyncVO>> syncMerchantOrder(
            @RequestBody(required = false) Mono<OpenApiOrderSyncDTO> bodyMono) {
        if (bodyMono == null) {
            return Mono.just(Result.success(new OpenApiOrderSyncVO(
                    "SYNC-EMPTY", "EMPTY", "EMPTY_BODY", System.currentTimeMillis(), true)));
        }
        return bodyMono
                .map(dto -> {
                    String orderId = dto.getOrderId() != null ? dto.getOrderId() : UUID.randomUUID().toString();
                    return Result.success(new OpenApiOrderSyncVO(
                            "SYNC-" + orderId,
                            orderId,
                            "SYNCED",
                            System.currentTimeMillis(),
                            true
                    ));
                })
                .defaultIfEmpty(Result.success(new OpenApiOrderSyncVO(
                        "SYNC-EMPTY", "EMPTY", "EMPTY_BODY", System.currentTimeMillis(), true)));
    }

    /**
     * 开放平台订单查询接口 (GET 签名校验)
     *
     * @param orderId 待查询订单号
     * @return 订单查询响应视图
     */
    @GetMapping("/open/merchant/query-order")
    public Mono<Result<OpenApiOrderSyncVO>> queryMerchantOrder(
            @RequestParam(value = "orderId", defaultValue = "ORD-SAMPLE-001") String orderId) {
        return Mono.just(Result.success(new OpenApiOrderSyncVO(
                "QUERY-" + orderId,
                orderId,
                "QUERY_OK",
                System.currentTimeMillis(),
                true
        )));
    }

    /**
     * 载荷防爆防御检测接口 (验证 Netty 2MB 限制与请求体接收)
     *
     * @param bodyMono 请求体字节流
     * @return 载荷接收与限制比对结果
     */
    @PostMapping("/payload-guard")
    public Mono<Result<PayloadResultVO>> payloadGuard(@RequestBody(required = false) Mono<byte[]> bodyMono) {
        if (bodyMono == null) {
            return Mono.just(Result.success(new PayloadResultVO(0, MAX_ALLOWED_BYTES, true)));
        }
        return bodyMono
                .map(bytes -> Result.success(new PayloadResultVO(bytes.length, MAX_ALLOWED_BYTES, true)))
                .defaultIfEmpty(Result.success(new PayloadResultVO(0, MAX_ALLOWED_BYTES, true)));
    }

    /**
     * IP 边缘安全守卫放行探测终结点
     *
     * @param request 响应式 HTTP 请求
     * @return 边缘安全检测状态
     */
    @GetMapping("/ip-guard")
    public Mono<Result<PerimeterSecurityVO>> ipGuard(ServerHttpRequest request) {
        String clientIp = request.getHeaders().getFirst("X-Real-IP");
        return Mono.just(Result.success(new PerimeterSecurityVO(
                clientIp,
                request.getPath().value(),
                "IP_ALLOWED",
                Collections.emptyMap()
        )));
    }

    /**
     * URI 路径防卫与规范化探测终结点
     *
     * @param request 响应式 HTTP 请求
     * @return 路径规范化放行状态
     */
    @GetMapping("/path-guard")
    public Mono<Result<PerimeterSecurityVO>> pathGuard(ServerHttpRequest request) {
        return Mono.just(Result.success(new PerimeterSecurityVO(
                null,
                request.getPath().value(),
                "PATH_NORMALIZED",
                Collections.emptyMap()
        )));
    }

    /**
     * 边缘标头清洗与真实 IP 倒推解析检查接口
     *
     * @param request 响应式 HTTP 请求
     * @return 下游实际接收到的清理后标头字典
     */
    @GetMapping("/inspect-edge-headers")
    public Mono<Result<Map<String, String>>> inspectEdgeHeaders(ServerHttpRequest request) {
        Map<String, String> headerMap = new HashMap<>();
        request.getHeaders().forEach((name, values) -> {
            if (values != null && !values.isEmpty()) {
                headerMap.put(name.toLowerCase(), values.get(0));
            }
        });
        return Mono.just(Result.success(headerMap));
    }
}
