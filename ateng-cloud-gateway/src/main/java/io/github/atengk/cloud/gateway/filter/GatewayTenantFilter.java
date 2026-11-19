package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTenantProperties;
import io.github.atengk.cloud.gateway.spi.GatewayTenantSwitchValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 多租户入站解析与防跨越隔离全局过滤器
 *
 * <p>置于网关集中鉴权之后的下游管道（{@link GatewayConstants#ORDER_TENANT}）：
 * <ul>
 *     <li><b>鉴权优先原则</b>：已登录认证会话强制使用登录态/Token 载荷绑定的真实 {@code tenantId}
 *     覆盖出站标头，彻底杜绝恶意客户端伪造或串改租户标头引发的多租户数据泄露；</li>
 *     <li><b>平台超管受控切换</b>：仅当显式开启 {@code allow-super-admin-switch=true}、会话具备超管身份且命中
 *     {@code platform-tenant-ids}（为空时不限制）时，才允许在校验格式与可选 {@link GatewayTenantSwitchValidator}
 *     响应式 SPI 通过后受控切换目标租户；</li>
 *     <li><b>受控开放解析</b>：未登录的开放/白名单流量，仅当配置 {@code ateng.cloud.gateway.tenant.allow-header=true}
 *     时才允许读取入站标头；若未开启则坚决剥离置空，实现零信任防线。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewayTenantFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayTenantFilter.class);

    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$");

    private final AtengGatewayTenantProperties tenantProperties;
    private final GatewayTenantSwitchValidator switchValidator;

    public GatewayTenantFilter(AtengGatewayTenantProperties tenantProperties) {
        this(tenantProperties, (GatewayTenantSwitchValidator) null);
    }

    public GatewayTenantFilter(AtengGatewayTenantProperties tenantProperties,
                               ObjectProvider<GatewayTenantSwitchValidator> switchValidatorProvider) {
        this(tenantProperties, switchValidatorProvider != null ? switchValidatorProvider.getIfAvailable() : null);
    }

    public GatewayTenantFilter(AtengGatewayTenantProperties tenantProperties,
                               GatewayTenantSwitchValidator switchValidator) {
        this.tenantProperties = tenantProperties;
        this.switchValidator = switchValidator;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String headerName = (tenantProperties != null && StringUtils.hasText(tenantProperties.getHeaderName()))
                ? tenantProperties.getHeaderName().trim()
                : GatewayConstants.X_TENANT_ID;

        // 1. 提取由登录态解析出的受信任租户与认证状态 (鉴权优先原则)
        String authenticatedTenantId = exchange.getAttribute(GatewayConstants.ATTR_TENANT_ID);
        boolean isAuthenticated = Boolean.TRUE.equals(exchange.getAttribute(GatewayConstants.ATTR_AUTHENTICATED))
                || StringUtils.hasText(exchange.getAttribute(GatewayConstants.ATTR_USER_ID))
                || StringUtils.hasText(authenticatedTenantId);

        if (isAuthenticated) {
            if (canSuperAdminSwitch(exchange, authenticatedTenantId)) {
                String requestedTenantId = resolveRequestedTenantId(exchange, headerName);
                if (StringUtils.hasText(requestedTenantId)) {
                    return handleSuperAdminTenantSwitch(
                            exchange, chain, headerName, authenticatedTenantId, requestedTenantId.trim());
                }
            }
            return applyAuthenticatedTenantHeader(exchange, chain, headerName, authenticatedTenantId);
        }

        // 2. 未登录/开放/白名单流量受控解析
        boolean allowHeader = tenantProperties != null && tenantProperties.isAllowHeader();
        if (allowHeader) {
            String incomingTenantId = exchange.getRequest().getHeaders().getFirst(headerName);
            if (StringUtils.hasText(incomingTenantId)) {
                String cleanTenantId = incomingTenantId.trim();
                exchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, cleanTenantId);
                return chain.filter(exchange);
            }
        } else {
            // 若未开启 allowHeader，确保未登录请求绝对不得携带伪造的 X-Tenant-Id
            if (exchange.getRequest().getHeaders().containsKey(headerName)) {
                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                        .headers(httpHeaders -> httpHeaders.remove(headerName))
                        .build();
                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            }
        }

        return chain.filter(exchange);
    }

    private Mono<Void> applyAuthenticatedTenantHeader(ServerWebExchange exchange,
                                                      GatewayFilterChain chain,
                                                      String headerName,
                                                      String authenticatedTenantId) {
        if (StringUtils.hasText(authenticatedTenantId)) {
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header(headerName, authenticatedTenantId)
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }
        if (exchange.getRequest().getHeaders().containsKey(headerName)) {
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .headers(httpHeaders -> httpHeaders.remove(headerName))
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }
        return chain.filter(exchange);
    }

    private Mono<Void> handleSuperAdminTenantSwitch(ServerWebExchange exchange,
                                                    GatewayFilterChain chain,
                                                    String headerName,
                                                    String sessionTenantId,
                                                    String targetTenantId) {
        if (!isValidTenantIdFormat(targetTenantId)) {
            log.warn("网关拦截非法格式的超管切换租户请求头: targetTenantId=[{}], userId=[{}]",
                    targetTenantId, exchange.getAttribute(GatewayConstants.ATTR_USER_ID));
            return Mono.error(new BusinessException(CommonResultCode.BAD_REQUEST, "非法租户标识格式"));
        }
        if (switchValidator != null) {
            String userId = exchange.getAttribute(GatewayConstants.ATTR_USER_ID);
            return switchValidator.validate(exchange, targetTenantId, sessionTenantId, userId)
                    .defaultIfEmpty(Boolean.FALSE)
                    .flatMap(allowed -> {
                        if (!Boolean.TRUE.equals(allowed)) {
                            log.warn("网关超管租户切换未通过校验 SPI: targetTenantId=[{}], sessionTenantId=[{}], userId=[{}]",
                                    targetTenantId, sessionTenantId, userId);
                            return Mono.error(new BusinessException(CommonResultCode.FORBIDDEN, "无权切换或访问目标租户"));
                        }
                        return applySwitchedTenantHeader(exchange, chain, headerName, targetTenantId);
                    });
        }
        return applySwitchedTenantHeader(exchange, chain, headerName, targetTenantId);
    }

    private Mono<Void> applySwitchedTenantHeader(ServerWebExchange exchange,
                                                 GatewayFilterChain chain,
                                                 String headerName,
                                                 String targetTenantId) {
        exchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, targetTenantId);
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(headerName, targetTenantId)
                .build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private boolean canSuperAdminSwitch(ServerWebExchange exchange, String sessionTenantId) {
        if (tenantProperties == null || !tenantProperties.isAllowSuperAdminSwitch()) {
            return false;
        }
        if (!Boolean.TRUE.equals(exchange.getAttribute(GatewayConstants.ATTR_SUPER_ADMIN))) {
            return false;
        }
        return isPlatformTenantAllowed(tenantProperties.getPlatformTenantIds(), sessionTenantId);
    }

    private static boolean isPlatformTenantAllowed(List<String> platformTenantIds, String sessionTenantId) {
        if (platformTenantIds == null || platformTenantIds.isEmpty()) {
            return true;
        }
        boolean hasConfiguredPlatformTenant = false;
        String normalizedSessionTenant = StringUtils.hasText(sessionTenantId) ? sessionTenantId.trim() : null;
        for (String candidate : platformTenantIds) {
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            hasConfiguredPlatformTenant = true;
            if (normalizedSessionTenant != null && candidate.trim().equals(normalizedSessionTenant)) {
                return true;
            }
        }
        return !hasConfiguredPlatformTenant;
    }

    private String resolveRequestedTenantId(ServerWebExchange exchange, String headerName) {
        String captured = exchange.getAttribute(GatewayConstants.ATTR_REQUESTED_TENANT_ID);
        if (StringUtils.hasText(captured)) {
            return captured;
        }
        return exchange.getRequest().getHeaders().getFirst(headerName);
    }

    private static boolean isValidTenantIdFormat(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return false;
        }
        if (tenantId.contains("..") || tenantId.endsWith(".")) {
            return false;
        }
        return TENANT_ID_PATTERN.matcher(tenantId).matches();
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_TENANT;
    }
}
