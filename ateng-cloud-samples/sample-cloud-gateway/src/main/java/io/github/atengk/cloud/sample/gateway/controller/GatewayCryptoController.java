package io.github.atengk.cloud.sample.gateway.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.sample.gateway.model.SecureCheckoutDTO;
import io.github.atengk.cloud.sample.gateway.model.SecureCheckoutVO;
import io.github.atengk.cloud.sample.gateway.model.SpaProfileVO;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 网关边缘 API 混合加解密与 Encrypt-then-Sign 端到端示例控制器（纯响应式）
 *
 * @author Ateng
 * @since 2026-11-27
 */
@RestController
@RequestMapping("/sample/cloud/gateway/crypto")
public class GatewayCryptoController {

    /**
     * 敏感结算接口（测试入站解密、加密头剥离与出站加密回签）
     *
     * @param bodyMono 解密后的请求 DTO
     * @param request  响应式请求对象
     * @return 加密前明文业务视图（网关在出站阶段按策略加密）
     */
    @PostMapping("/checkout")
    public Mono<Result<SecureCheckoutVO>> checkout(
            @RequestBody Mono<SecureCheckoutDTO> bodyMono,
            ServerHttpRequest request) {
        String appId = request.getHeaders().getFirst(GatewayConstants.X_APP_ID);
        boolean encryptHeaderVisible = request.getHeaders().containsKey(GatewayConstants.X_ENCRYPT_KEY)
                || request.getHeaders().containsKey(GatewayConstants.X_ENCRYPT_IV);
        return bodyMono.map(dto -> Result.success(new SecureCheckoutVO(
                dto.getOrderNo(),
                dto.getAmountCents(),
                appId,
                encryptHeaderVisible,
                "CHECKOUT_ACCEPTED"
        )));
    }

    /**
     * 浏览器 SPA 无静态私钥加密模式 GET 查询接口（通过请求头一次性会话密钥握手）
     *
     * @return 明文响应视图（由网关复用请求解封会话密钥并生成新随机 IV 加密）
     */
    @GetMapping("/spa/profile")
    public Mono<Result<SpaProfileVO>> spaProfile() {
        return Mono.just(Result.success(new SpaProfileVO(
                "Ateng-Architect",
                "138****8888",
                "SPA_SESSION_CRYPTO"
        )));
    }

    /**
     * SSE 流式接口放行验证（自动跳过整包加解密避免阻塞长连接流）
     *
     * @return SSE 事件流
     */
    @GetMapping(value = "/stream/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamEvents() {
        return Flux.just(
                ServerSentEvent.<String>builder().id("1").data("crypto-sse-bypass-1").build(),
                ServerSentEvent.<String>builder().id("2").data("crypto-sse-bypass-2").build()
        );
    }
}
