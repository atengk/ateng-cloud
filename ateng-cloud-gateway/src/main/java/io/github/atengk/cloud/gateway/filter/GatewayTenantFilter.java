package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTenantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 多租户入站解析与防跨越隔离全局过滤器
 *
 * <p>置于网关集中鉴权之后的下游管道（{@link GatewayConstants#ORDER_TENANT}）：
 * <ul>
 *     <li><b>鉴权优先原则</b>：已登录认证会话强制使用登录态/Token 载荷绑定的真实 {@code tenantId}
 *     覆盖出站标头，彻底杜绝恶意客户端伪造或串改租户标头引发的多租户数据泄露；</li>
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

    private final AtengGatewayTenantProperties tenantProperties;

    public GatewayTenantFilter(AtengGatewayTenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String headerName = (tenantProperties != null && StringUtils.hasText(tenantProperties.getHeaderName()))
                ? tenantProperties.getHeaderName()
                : GatewayConstants.X_TENANT_ID;

        // 1. 优先提取由登录态解析出的受信任租户标识 (鉴权优先原则)
        String authenticatedTenantId = exchange.getAttribute(GatewayConstants.ATTR_TENANT_ID);

        if (StringUtils.hasText(authenticatedTenantId)) {
            // 已登录认证会话：强行覆盖出站标头
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header(headerName, authenticatedTenantId)
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
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

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_TENANT;
    }
}
