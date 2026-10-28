package io.github.atengk.cloud.gateway.filter;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.same.SaSameUtil;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sa-Token 响应式微服务网关集中安全鉴权与零信任同源防御全局过滤器
 *
 * <p>置于网关鉴权关键防线（{@link GatewayConstants#ORDER_SA_TOKEN}）：
 * <ul>
 *     <li>自动合并系统默认白名单与配置属性中的排除路径（{@code exclude-paths}），对白名单请求执行免密直通；</li>
 *     <li>对白名单与鉴权通过的流量，出站向下游统一注入网关动态签发的 {@code Same-Token} 内部同源受信凭据；</li>
 *     <li>对受保护接口未登录或 Token 非法/已过期请求，Fail-Fast 熔断并返回 HTTP 401 统一契约；</li>
 *     <li>鉴权通过后动态从登录态中解出 {@code userId} 与 {@code tenantId}，权威注入下游微服务请求头。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class SaTokenGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SaTokenGatewayFilter.class);

    private static final List<String> DEFAULT_SYSTEM_WHITELIST = Arrays.asList(
            "/actuator/**",
            "/docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/favicon.ico"
    );

    private final AtengGatewaySecurityProperties securityProperties;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public SaTokenGatewayFilter(AtengGatewaySecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
        // 定制 Sa-Token 防火墙检测失败处理逻辑，转译为统一 HTTP 400 Bad Request
        cn.dev33.satoken.strategy.SaFirewallStrategy.instance.checkFailHandle = (e, req, res, ext) -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法请求路径：检测到目录穿越或畸形字符", e);
        };
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (!StringUtils.hasText(path)) {
            path = "/";
        }

        // 1. 检查是否命中系统默认或配置白名单
        if (isWhitelisted(path)) {
            log.debug("网关白名单放行: URI=[{}]", path);
            SaReactorSyncHolder.setContext(exchange);
            try {
                ServerHttpRequest.Builder reqBuilder = exchange.getRequest().mutate();
                if (StpUtil.isLogin()) {
                    Object loginId = StpUtil.getLoginIdDefaultNull();
                    String userId = loginId != null ? String.valueOf(loginId) : null;
                    String tenantId = extractTenantId();

                    if (StringUtils.hasText(userId)) {
                        exchange.getAttributes().put(GatewayConstants.ATTR_USER_ID, userId);
                        reqBuilder.header(GatewayConstants.X_USER_ID, userId);
                    }
                    if (StringUtils.hasText(tenantId)) {
                        exchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, tenantId);
                        reqBuilder.header(GatewayConstants.X_TENANT_ID, tenantId);
                    }
                }
                injectSameTokenHeaders(reqBuilder);
                return chain.filter(exchange.mutate().request(reqBuilder.build()).build());
            } catch (Exception e) {
                ServerHttpRequest mutatedRequest = injectSameToken(exchange.getRequest());
                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            } finally {
                SaReactorSyncHolder.clearContext();
            }
        }

        // 2. 集中鉴权与同源签名注入
        SaReactorSyncHolder.setContext(exchange);
        try {
            if (!StpUtil.isLogin()) {
                log.warn("网关集中鉴权：未携带合法 Token 或已过期，阻断访问: URI=[{}]", path);
                return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录状态已过期，请重新登录"));
            }

            // 3. 提取受信任的用户与租户身份信息
            Object loginId = StpUtil.getLoginIdDefaultNull();
            String userId = loginId != null ? String.valueOf(loginId) : null;
            String tenantId = extractTenantId();

            if (StringUtils.hasText(userId)) {
                exchange.getAttributes().put(GatewayConstants.ATTR_USER_ID, userId);
            }
            if (StringUtils.hasText(tenantId)) {
                exchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, tenantId);
            }

            // 4. 重写入站下游请求头
            ServerHttpRequest.Builder reqBuilder = exchange.getRequest().mutate();
            if (StringUtils.hasText(userId)) {
                reqBuilder.header(GatewayConstants.X_USER_ID, userId);
            }
            if (StringUtils.hasText(tenantId)) {
                reqBuilder.header(GatewayConstants.X_TENANT_ID, tenantId);
            }

            // 注入网关动态签发的内部同源 Same-Token
            injectSameTokenHeaders(reqBuilder);

            return chain.filter(exchange.mutate().request(reqBuilder.build()).build());
        } catch (NotLoginException e) {
            log.warn("网关集中鉴权：未登录异常: URI=[{}], type=[{}]", path, e.getType());
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录状态已过期，请重新登录"));
        } finally {
            SaReactorSyncHolder.clearContext();
        }
    }

    /**
     * 判断当前路径是否命中系统默认白名单或业务自定义白名单
     */
    private boolean isWhitelisted(String path) {
        // 匹配系统预设白名单
        for (String pattern : DEFAULT_SYSTEM_WHITELIST) {
            if (antPathMatcher.match(pattern, path)) {
                return true;
            }
        }

        // 匹配业务自定义排除路径
        if (securityProperties != null && securityProperties.getExcludePaths() != null) {
            for (String pattern : securityProperties.getExcludePaths()) {
                if (StringUtils.hasText(pattern) && antPathMatcher.match(pattern.trim(), path)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 自适应提取当前登录会话中的租户 ID (兼容 sa-token-jwt extra 与底层 SaSession)
     */
    private String extractTenantId() {
        try {
            Object tenantIdObj = StpUtil.getExtra("tenantId");
            if (tenantIdObj != null) {
                return String.valueOf(tenantIdObj);
            }
        } catch (Exception ignored) {
            // sa-token-jwt 插件未引入时降级为从 Session 提取
        }
        try {
            if (StpUtil.isLogin()) {
                cn.dev33.satoken.session.SaSession session = StpUtil.getSession(false);
                if (session != null) {
                    Object tenantIdObj = session.get("tenantId");
                    if (tenantIdObj != null) {
                        return String.valueOf(tenantIdObj);
                    }
                }
                cn.dev33.satoken.session.SaSession tokenSession = StpUtil.getTokenSession();
                if (tokenSession != null) {
                    Object tenantIdObj = tokenSession.get("tenantId");
                    if (tenantIdObj != null) {
                        return String.valueOf(tenantIdObj);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 向出站请求注入动态签发的 Same-Token
     */
    private ServerHttpRequest injectSameToken(ServerHttpRequest request) {
        ServerHttpRequest.Builder builder = request.mutate();
        injectSameTokenHeaders(builder);
        return builder.build();
    }

    private void injectSameTokenHeaders(ServerHttpRequest.Builder builder) {
        try {
            String sameToken = SaSameUtil.getToken();
            if (StringUtils.hasText(sameToken)) {
                builder.header(GatewayConstants.SAME_TOKEN, sameToken);
                builder.header(GatewayConstants.SAME_TOKEN_UPPER, sameToken);
            }
        } catch (Exception e) {
            log.debug("网关签发 Same-Token 跳过: [{}]", e.getMessage());
        }
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_SA_TOKEN;
    }
}
