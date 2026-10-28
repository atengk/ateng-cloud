package io.github.atengk.cloud.sample.gateway.controller;

import cn.dev33.satoken.stp.SaLoginConfig;
import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * 响应式认证与登录 Mock 终结点
 *
 * @author Ateng
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @GetMapping("/login")
    public Mono<Result<Map<String, String>>> login(
            @RequestParam(defaultValue = "10001") String userId,
            @RequestParam(defaultValue = "tenant-888") String tenantId,
            org.springframework.web.server.ServerWebExchange exchange) {
        return Mono.fromCallable(() -> cn.dev33.satoken.reactor.context.SaReactorSyncHolder.setContext(exchange, () -> {
            StpUtil.login(userId);
            StpUtil.getSession().set("tenantId", tenantId);
            StpUtil.getTokenSession().set("tenantId", tenantId);
            SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
            Map<String, String> data = new HashMap<>();
            data.put("tokenName", tokenInfo.getTokenName());
            data.put("tokenValue", tokenInfo.getTokenValue());
            return Result.success(data);
        }));
    }
}
