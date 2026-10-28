package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayAccessLogProperties;
import io.github.atengk.cloud.gateway.util.ClientIpResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 网关轻量响应式访问日志审计与慢请求度量告警全局过滤器
 *
 * <p>核心治理语义：
 * 1. 采用 Reactor {@code doFinally} 零阻塞异步度量请求往返耗时；
 * 2. 结构化单行输出调用方法、请求路径、响应状态码、执行耗时、客户端 IP 及 TraceId 等核心指标；
 * 3. 针对超过慢请求阈值（默认 3s）的接口打印 WARN 级别慢请求告警。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewayAccessLogFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayAccessLogFilter.class);

    private final AtengGatewayAccessLogProperties properties;
    private final PathMatcher pathMatcher = new AntPathMatcher();

    public GatewayAccessLogFilter(AtengGatewayAccessLogProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (properties == null || !properties.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        if (isExcluded(path)) {
            return chain.filter(exchange);
        }

        long startTime = System.currentTimeMillis();

        return chain.filter(exchange).doFinally(signalType -> {
            long duration = System.currentTimeMillis() - startTime;
            logAccess(exchange, path, duration);
        });
    }

    /**
     * 判断当前路径是否在排除日志审计列表中
     */
    private boolean isExcluded(String path) {
        List<String> excludePaths = properties.getExcludePaths();
        if (excludePaths == null || excludePaths.isEmpty()) {
            return false;
        }
        for (String pattern : excludePaths) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 输出结构化访问日志与慢请求告警
     */
    private void logAccess(ServerWebExchange exchange, String path, long duration) {
        String method = exchange.getRequest().getMethod().name();
        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
        int status = statusCode != null ? statusCode.value() : 200;

        String clientIp = (String) exchange.getAttributes().get(GatewayConstants.ATTR_CLIENT_IP);
        if (!StringUtils.hasText(clientIp)) {
            clientIp = ClientIpResolver.resolveClientIp(exchange.getRequest(), 1);
        }

        String traceId = (String) exchange.getAttributes().get(GatewayConstants.ATTR_TRACE_ID);
        if (!StringUtils.hasText(traceId)) {
            traceId = exchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TRACE_ID);
        }

        long slowThresholdMs = properties.getSlowThreshold() != null
                ? properties.getSlowThreshold().toMillis()
                : 3000L;

        if (duration >= slowThresholdMs) {
            log.warn("[GatewayAccessLog] SLOW REQUEST: method={}, path={}, status={}, duration={}ms > {}ms, ip={}, traceId={}",
                    method, path, status, duration, slowThresholdMs, clientIp, traceId);
        } else {
            log.info("[GatewayAccessLog] method={}, path={}, status={}, duration={}ms, ip={}, traceId={}",
                    method, path, status, duration, clientIp, traceId);
        }
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_ACCESS_LOG;
    }
}
