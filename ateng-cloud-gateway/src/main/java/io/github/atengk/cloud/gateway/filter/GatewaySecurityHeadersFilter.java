package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityHeadersProperties;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * OWASP 等保三级合规安全响应头强化与跨域出站标头去重响应式 Web 过滤器
 *
 * <p>核心治理语义：
 * 1. 响应出站自动注入 X-Content-Type-Options、X-Frame-Options、X-XSS-Protection、HSTS 与 Referrer-Policy；
 * 2. 自动对 Access-Control-Allow-Origin 与 Access-Control-Allow-Credentials 执行去重，保留首项以杜绝浏览器 CORS 冲突阻断。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewaySecurityHeadersFilter implements WebFilter, Ordered {

    private final AtengGatewaySecurityHeadersProperties properties;

    public GatewaySecurityHeadersFilter(AtengGatewaySecurityHeadersProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (properties == null || !properties.isEnabled()) {
            return chain.filter(exchange);
        }

        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();

            // 1. 防 MIME 嗅探
            if (StringUtils.hasText(properties.getContentTypeOptions()) && !headers.containsKey("X-Content-Type-Options")) {
                headers.set("X-Content-Type-Options", properties.getContentTypeOptions());
            }

            // 2. 防点击劫持
            if (StringUtils.hasText(properties.getFrameOptions()) && !headers.containsKey("X-Frame-Options")) {
                headers.set("X-Frame-Options", properties.getFrameOptions());
            }

            // 3. 浏览器 XSS 过滤器
            if (StringUtils.hasText(properties.getXssProtection()) && !headers.containsKey("X-XSS-Protection")) {
                headers.set("X-XSS-Protection", properties.getXssProtection());
            }

            // 4. 强制传输安全 HSTS
            if (StringUtils.hasText(properties.getHsts()) && !headers.containsKey("Strict-Transport-Security")) {
                headers.set("Strict-Transport-Security", properties.getHsts());
            }

            // 5. 来源跳转安全策略
            if (StringUtils.hasText(properties.getReferrerPolicy()) && !headers.containsKey("Referrer-Policy")) {
                headers.set("Referrer-Policy", properties.getReferrerPolicy());
            }

            // 6. 出站 CORS 标头去重 (保留首个，杜绝浏览器跨域冲突报错)
            List<String> origins = headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
            if (origins != null && origins.size() > 1) {
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origins.get(0));
            }
            List<String> credentials = headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
            if (credentials != null && credentials.size() > 1) {
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, credentials.get(0));
            }

            return Mono.empty();
        });

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_SECURITY_HEADERS;
    }
}
