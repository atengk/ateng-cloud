package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.spi.IpFilterProvider;
import io.github.atengk.cloud.gateway.util.ClientIpResolver;
import io.github.atengk.cloud.gateway.util.IpMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * IP 黑白名单防卫全局过滤器
 *
 * <p>置于网关入站防御管道（{@link GatewayConstants#ORDER_IP_FILTER}），依据 {@link IpFilterProvider}
 * 提供的防护规则实施毫秒级快速过滤：
 * <ul>
 *     <li>若客户端真实 IP 命中黑名单，直接以 HTTP 403 Forbidden 熔断阻断；</li>
 *     <li>若配置了白名单且客户端 IP 未在白名单授权范围内，同样以 HTTP 403 熔断阻断；</li>
 *     <li>验证通过方可流转至下游后续鉴权与业务路由管道。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewayIpFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayIpFilter.class);

    private final IpFilterProvider ipFilterProvider;

    public GatewayIpFilter(IpFilterProvider ipFilterProvider) {
        this.ipFilterProvider = ipFilterProvider;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (ipFilterProvider == null) {
            return chain.filter(exchange);
        }

        // 1. 获取已由 GatewayClientIpFilter 解析出的权威真实 IP，兜底自行解析
        String clientIp = exchange.getAttribute(GatewayConstants.ATTR_CLIENT_IP);
        if (!StringUtils.hasText(clientIp)) {
            clientIp = ClientIpResolver.resolveClientIp(exchange.getRequest(), 1);
        }

        // 2. 检查黑名单规则 (优先阻断)
        List<String> blacklist = ipFilterProvider.getBlacklist();
        if (blacklist != null && !blacklist.isEmpty() && IpMatcher.matchesAny(clientIp, blacklist)) {
            log.warn("网关边缘防卫：客户端 IP [{}] 命中黑名单，已阻断访问: URI=[{}]",
                    clientIp, exchange.getRequest().getURI().getRawPath());
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "访问受限：IP已被列入黑名单"));
        }

        // 3. 检查白名单规则 (非空时启用受限白名单模式)
        List<String> whitelist = ipFilterProvider.getWhitelist();
        if (whitelist != null && !whitelist.isEmpty() && !IpMatcher.matchesAny(clientIp, whitelist)) {
            log.warn("网关边缘防卫：客户端 IP [{}] 未在白名单授权范围，已阻断访问: URI=[{}]",
                    clientIp, exchange.getRequest().getURI().getRawPath());
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "访问受限：IP未在白名单授权范围"));
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_IP_FILTER;
    }
}
