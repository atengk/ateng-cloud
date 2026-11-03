package io.github.atengk.cloud.sample.gateway.controller;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.sample.gateway.model.AuthTokenVO;
import io.github.atengk.cloud.sample.gateway.model.GatewaySecurityContextVO;
import io.github.atengk.cloud.sample.gateway.model.UserInfoVO;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * 响应式网关集中认证、会话管理与零信任同源防御控制器
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/gateway/auth")
public class GatewayAuthController {

    /**
     * 用户统一认证登录，签发 Sa-Token 令牌并绑定多租户隔离上下文
     *
     * @param userId   用户唯一标识
     * @param tenantId 租户唯一标识
     * @param exchange 响应式 HTTP 请求上下文
     * @return 包含 Token 凭据与生命周期的强类型契约
     */
    @GetMapping("/login")
    public Mono<Result<AuthTokenVO>> login(
            @RequestParam(defaultValue = "10001") String userId,
            @RequestParam(defaultValue = "tenant-888") String tenantId,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> SaReactorSyncHolder.setContext(exchange, () -> {
            StpUtil.login(userId);
            StpUtil.getSession().set("tenantId", tenantId);
            StpUtil.getTokenSession().set("tenantId", tenantId);
            SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
            AuthTokenVO tokenVO = new AuthTokenVO(
                    tokenInfo.getTokenName(),
                    tokenInfo.getTokenValue(),
                    userId,
                    tenantId,
                    tokenInfo.getTokenTimeout()
            );
            return Result.success(tokenVO);
        }));
    }

    /**
     * 查询当前登录用户信息（受保护接口，未登录时由网关层 Fail-Fast 401 阻断）
     *
     * @param exchange 响应式 HTTP 请求上下文
     * @return 当前登录用户安全状态视图
     */
    @GetMapping("/user-info")
    public Mono<Result<UserInfoVO>> userInfo(ServerWebExchange exchange) {
        return Mono.fromCallable(() -> SaReactorSyncHolder.setContext(exchange, () -> {
            String userId = StpUtil.getLoginIdAsString();
            String tenantId = (String) StpUtil.getTokenSession().get("tenantId");
            return Result.success(new UserInfoVO(userId, tenantId, "ACTIVE"));
        }));
    }

    /**
     * 回显下游微服务接收到的受信任安全上下文（用于断言零信任隔离、Same-Token 注入与标头清洗）
     *
     * @param request 响应式 HTTP 入站请求
     * @return 聚合下游接收到的受信标头视图模型
     */
    @GetMapping("/inspect-context")
    public Mono<Result<GatewaySecurityContextVO>> inspectContext(ServerHttpRequest request) {
        Map<String, String> headerMap = new HashMap<>();
        request.getHeaders().forEach((name, values) -> {
            if (values != null && !values.isEmpty()) {
                headerMap.put(name.toLowerCase(), values.get(0));
            }
        });
        GatewaySecurityContextVO contextVO = new GatewaySecurityContextVO(
                headerMap.get(GatewayConstants.X_TRACE_ID.toLowerCase()),
                headerMap.get(GatewayConstants.X_USER_ID.toLowerCase()),
                headerMap.get(GatewayConstants.X_TENANT_ID.toLowerCase()),
                headerMap.get(GatewayConstants.SAME_TOKEN.toLowerCase()),
                headerMap.get(GatewayConstants.X_TRAFFIC_TAG.toLowerCase()),
                headerMap.get("x-client-ip"),
                headerMap
        );
        return Mono.just(Result.success(contextVO));
    }

    /**
     * 退出当前用户登录会话
     *
     * @param exchange 响应式 HTTP 请求上下文
     * @return 注销结果提示
     */
    @PostMapping("/logout")
    public Mono<Result<String>> logout(ServerWebExchange exchange) {
        return Mono.fromCallable(() -> SaReactorSyncHolder.setContext(exchange, () -> {
            if (StpUtil.isLogin()) {
                StpUtil.logout();
            }
            return Result.success("logout-success");
        }));
    }
}
