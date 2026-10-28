package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * URI 规范化与目录穿越防卫全局过滤器
 *
 * <p>置于网关南北向入站管道最前沿（{@link Ordered#HIGHEST_PRECEDENCE}），自动折叠规范化连续多斜杠（{@code ///} $\to$ {@code /}）；
 * 凡原始路径或 URL 解码后包含目录穿越特征（{@code ..}、{@code %2e%2e}）或空字节（{@code \0}、{@code %00}）的非法越权探测，
 * 立即以 HTTP 400 Bad Request 快速熔断，坚决阻断向内部微服务转发。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewayPathValidationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayPathValidationFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        URI uri = request.getURI();
        String rawPath = uri.getRawPath();

        if (!StringUtils.hasText(rawPath)) {
            return chain.filter(exchange);
        }

        // 1. 快速排查原始路径是否含有目录穿越特征或空字节
        if (containsTraversalOrIllegalChars(rawPath)) {
            log.warn("网关边缘防卫：检测到路径穿越或畸形字符探测，已阻断请求: URI=[{}]", rawPath);
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法请求路径：检测到目录穿越或畸形字符"));
        }

        // 2. 深入多层 URL 解码探测隐藏的编码绕过注入 (如 %2e%2e、%252e%252e)
        String currentPath = rawPath;
        for (int i = 0; i < 2; i++) {
            try {
                String decoded = URLDecoder.decode(currentPath, StandardCharsets.UTF_8);
                if (containsTraversalOrIllegalChars(decoded)) {
                    log.warn("网关边缘防卫：检测到编码绕过的路径穿越探测，已阻断请求: Decoded=[{}]", decoded);
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法请求路径：检测到目录穿越或畸形字符"));
                }
                if (decoded.equals(currentPath)) {
                    break;
                }
                currentPath = decoded;
            } catch (IllegalArgumentException e) {
                log.warn("网关边缘防卫：请求路径 URL 解码异常，疑似畸形报文: path=[{}]", currentPath);
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法请求路径：URL 编码格式无效"));
            }
        }

        // 3. 规范化连续多斜杠 (/// -> /)
        String normalizedPath = rawPath.replaceAll("/{2,}", "/");
        if (!rawPath.equals(normalizedPath)) {
            ServerHttpRequest mutatedRequest = request.mutate()
                    .path(normalizedPath)
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }

        return chain.filter(exchange);
    }

    /**
     * 判断路径字符串是否包含路径穿越特征或空字节
     */
    private boolean containsTraversalOrIllegalChars(String path) {
        if (path == null) {
            return false;
        }

        String lower = path.toLowerCase(Locale.ROOT);

        // 空字节探测 (\0 或 %00)
        if (path.indexOf('\0') >= 0 || lower.contains("%00")) {
            return true;
        }

        // 十六进制编码点号 (%2e%2e, %2e., .%2e)
        if (lower.contains("%2e%2e") || lower.contains("%2e.") || lower.contains(".%2e")) {
            return true;
        }

        // 标准双点号目录穿越 (..)
        if (lower.contains("..")) {
            return true;
        }

        return false;
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_PATH_VALIDATION;
    }
}
