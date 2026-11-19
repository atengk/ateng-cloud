package io.github.atengk.cloud.sample.gateway.controller;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpLogic;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.filter.SaTokenGatewayFilter;
import io.github.atengk.cloud.sample.gateway.model.AuthTokenVO;
import io.github.atengk.cloud.sample.gateway.model.GatewaySecurityContextVO;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 响应式网关多账号体系 (admin / member) 与 LoginUser 会话协议演练控制器
 *
 * @author Ateng
 * @since 2026-11-18
 */
@RestController
@RequestMapping("/sample/cloud/gateway/auth")
public class GatewayMultiAccountAuthController {

    /**
     * 管理端账号体系登录 (loginType = admin，复合 loginId = {tenantId}_{userId}，Account Session 存储 login_user JSON)
     *
     * @param userId      用户 ID
     * @param tenantId    租户 ID
     * @param runAsUserId 可选会话模拟目标用户 ID (switch_target_user_id)
     * @param exchange    响应式请求上下文
     * @return 签发的 satoken-admin 令牌视图
     */
    @GetMapping("/admin/login")
    public Mono<Result<AuthTokenVO>> adminLogin(
            @RequestParam(defaultValue = "1001") String userId,
            @RequestParam(defaultValue = "000000") String tenantId,
            @RequestParam(required = false) String runAsUserId,
            @RequestParam(required = false) String roles,
            ServerWebExchange exchange) {
        validateLoginIdentifiers(userId, tenantId);
        return Mono.fromCallable(() -> SaReactorSyncHolder.setContext(exchange, () -> {
            StpLogic adminLogic = SaTokenGatewayFilter.getOrCreateStpLogic("admin");
            String cleanUserId = userId.trim();
            String cleanTenantId = tenantId.trim();
            adminLogic.login(cleanTenantId + "_" + cleanUserId);

            Map<String, Object> loginUserPayload = buildLoginUserPayload(cleanUserId, cleanTenantId, "admin", roles);
            SaSession session = adminLogic.getSession();
            session.set("login_user", JsonUtil.toJsonString(loginUserPayload));
            if (StringUtils.hasText(runAsUserId)) {
                session.set("switch_target_user_id", runAsUserId.trim());
            } else {
                session.delete("switch_target_user_id");
            }

            SaTokenInfo tokenInfo = adminLogic.getTokenInfo();
            return Result.success(new AuthTokenVO(
                    tokenInfo.getTokenName(),
                    tokenInfo.getTokenValue(),
                    cleanUserId,
                    cleanTenantId,
                    tokenInfo.getTokenTimeout()
            ));
        }));
    }

    /**
     * 管理端受保护看板接口 (需携带有效 satoken-admin 凭据)
     *
     * @param request 响应式 HTTP 请求
     * @return 下游收到的受信安全上下文视图
     */
    @GetMapping("/admin/dashboard")
    public Mono<Result<GatewaySecurityContextVO>> adminDashboard(ServerHttpRequest request) {
        return Mono.just(Result.success(buildSecurityContextVO(request)));
    }

    /**
     * 管理端白名单公告接口 (免登录放行，若携带 satoken-admin 则同步提取身份)
     *
     * @param request 响应式 HTTP 请求
     * @return 下游收到的受信安全上下文视图
     */
    @GetMapping("/admin/public-notice")
    public Mono<Result<GatewaySecurityContextVO>> adminPublicNotice(ServerHttpRequest request) {
        return Mono.just(Result.success(buildSecurityContextVO(request)));
    }

    /**
     * C 端会员账号体系登录 (loginType = member，TokenSession 兜底存储 login_user JSON)
     *
     * @param userId   会员用户 ID
     * @param tenantId 租户 ID
     * @param exchange 响应式请求上下文
     * @return 签发的 satoken-member 令牌视图
     */
    @GetMapping("/app/login")
    public Mono<Result<AuthTokenVO>> memberLogin(
            @RequestParam(defaultValue = "2002") String userId,
            @RequestParam(defaultValue = "tenant-app") String tenantId,
            ServerWebExchange exchange) {
        validateLoginIdentifiers(userId, tenantId);
        return Mono.fromCallable(() -> SaReactorSyncHolder.setContext(exchange, () -> {
            StpLogic memberLogic = SaTokenGatewayFilter.getOrCreateStpLogic("member");
            String cleanUserId = userId.trim();
            String cleanTenantId = tenantId.trim();
            memberLogic.login(cleanTenantId + "_" + cleanUserId);

            Map<String, Object> loginUserPayload = buildLoginUserPayload(cleanUserId, cleanTenantId, "member", null);
            memberLogic.getTokenSession().set("login_user", JsonUtil.toJsonString(loginUserPayload));

            SaTokenInfo tokenInfo = memberLogic.getTokenInfo();
            return Result.success(new AuthTokenVO(
                    tokenInfo.getTokenName(),
                    tokenInfo.getTokenValue(),
                    cleanUserId,
                    cleanTenantId,
                    tokenInfo.getTokenTimeout()
            ));
        }));
    }

    /**
     * C 端会员受保护个人中心接口 (需携带有效 satoken-member 凭据)
     *
     * @param request 响应式 HTTP 请求
     * @return 下游收到的受信安全上下文视图
     */
    @GetMapping("/app/profile")
    public Mono<Result<GatewaySecurityContextVO>> memberProfile(ServerHttpRequest request) {
        return Mono.just(Result.success(buildSecurityContextVO(request)));
    }

    private void validateLoginIdentifiers(String userId, String tenantId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(tenantId)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "登录失败：userId 与 tenantId 不能为空");
        }
        if (tenantId.contains("_")) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "登录失败：tenantId 不允许包含下划线分隔符");
        }
    }

    private Map<String, Object> buildLoginUserPayload(String userId, String tenantId, String userType, String roles) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("tenantId", tenantId);
        payload.put("userType", userType);
        if (StringUtils.hasText(roles)) {
            payload.put("roles", java.util.Arrays.stream(roles.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .toList());
        }
        return payload;
    }

    private GatewaySecurityContextVO buildSecurityContextVO(ServerHttpRequest request) {
        Map<String, String> headerMap = new HashMap<>();
        request.getHeaders().forEach((name, values) -> {
            if (values != null && !values.isEmpty()) {
                headerMap.put(name.toLowerCase(), values.get(0));
            }
        });
        return new GatewaySecurityContextVO(
                headerMap.get(GatewayConstants.X_TRACE_ID.toLowerCase()),
                headerMap.get(GatewayConstants.X_USER_ID.toLowerCase()),
                headerMap.get(GatewayConstants.X_TENANT_ID.toLowerCase()),
                headerMap.get(GatewayConstants.SAME_TOKEN.toLowerCase()),
                headerMap.get(GatewayConstants.X_TRAFFIC_TAG.toLowerCase()),
                headerMap.get("x-client-ip"),
                headerMap
        );
    }
}
