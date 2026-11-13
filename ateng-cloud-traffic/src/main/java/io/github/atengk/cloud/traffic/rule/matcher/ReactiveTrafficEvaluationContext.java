package io.github.atengk.cloud.traffic.rule.matcher;

import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

/**
 * 基于 Spring WebFlux {@link ServerHttpRequest} 的响应式流量规则评估上下文适配器
 *
 * @author Ateng
 * @since 2026-11-13
 */
public class ReactiveTrafficEvaluationContext implements TrafficEvaluationContext {

    private final ServerHttpRequest request;

    /**
     * 构造响应式流量规则评估上下文
     *
     * @param request WebFlux 服务端请求
     */
    public ReactiveTrafficEvaluationContext(ServerHttpRequest request) {
        this.request = request;
    }

    /**
     * 基于 {@link ServerHttpRequest} 创建响应式评估上下文实例
     *
     * @param request WebFlux 服务端请求
     * @return 评估上下文适配器
     */
    public static ReactiveTrafficEvaluationContext from(ServerHttpRequest request) {
        return new ReactiveTrafficEvaluationContext(request);
    }

    @Override
    public String getHeader(String name) {
        if (request == null || name == null) {
            return null;
        }
        return request.getHeaders().getFirst(name);
    }

    @Override
    public String getQueryParam(String name) {
        if (request == null || name == null) {
            return null;
        }
        return request.getQueryParams().getFirst(name);
    }

    @Override
    public String getCookie(String name) {
        if (request == null || name == null) {
            return null;
        }
        HttpCookie directMatch = request.getCookies().getFirst(name);
        if (directMatch != null) {
            return directMatch.getValue();
        }
        for (Map.Entry<String, List<HttpCookie>> entry : request.getCookies().entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0).getValue();
            }
        }
        return null;
    }

    @Override
    public String getPath() {
        return request != null ? request.getURI().getPath() : null;
    }

    @Override
    public String getUserId() {
        return TrafficEvaluationContext.resolveUserId(
                request != null ? request.getHeaders().getFirst("X-User-Id") : null,
                request != null ? request.getQueryParams().getFirst("userId") : null);
    }

    @Override
    public String getTenantId() {
        return TrafficEvaluationContext.resolveTenantId(
                request != null ? request.getHeaders().getFirst("X-Tenant-Id") : null,
                request != null ? request.getQueryParams().getFirst("tenantId") : null);
    }

    @Override
    public String getClientIp() {
        if (request == null) {
            return null;
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        String remoteHost = null;
        if (remoteAddress != null) {
            remoteHost = remoteAddress.getAddress() != null
                    ? remoteAddress.getAddress().getHostAddress()
                    : remoteAddress.getHostString();
        }
        return TrafficEvaluationContext.resolveClientIp(
                request.getHeaders().getFirst("X-Forwarded-For"),
                request.getHeaders().getFirst("X-Real-IP"),
                remoteHost);
    }
}
