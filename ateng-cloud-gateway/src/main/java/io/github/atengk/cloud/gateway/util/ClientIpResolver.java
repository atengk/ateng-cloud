package io.github.atengk.cloud.gateway.util;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 真实公网客户端 IP 解析器
 *
 * <p>基于前置可信反向代理跳数（{@code trusted-proxy-count}）算法从右往左安全倒推解析真实公网 IP，
 * 有效防御恶意客户端伪造 {@code X-Forwarded-For} 实施的 IP 欺诈与黑名单绕过攻击。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public final class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    private static final String DEFAULT_FALLBACK_IP = "127.0.0.1";

    private ClientIpResolver() {
    }

    /**
     * 解析请求的权威真实公网客户端 IP
     *
     * @param request           响应式服务端请求对象
     * @param trustedProxyCount 前置可信反向代理层数 (>= 0)
     * @return 权威真实客户端 IP 字符串
     */
    public static String resolveClientIp(ServerHttpRequest request, int trustedProxyCount) {
        if (request == null) {
            return DEFAULT_FALLBACK_IP;
        }

        // 1. 提取物理套接字连接的直接对端 IP (Direct Socket Remote IP)
        String directRemoteIp = extractDirectRemoteIp(request);

        // 若不信任任何前置代理 (trustedProxyCount <= 0)，直接信任物理 TCP 连接对端
        if (trustedProxyCount <= 0) {
            return StringUtils.hasText(directRemoteIp) ? directRemoteIp : DEFAULT_FALLBACK_IP;
        }

        HttpHeaders headers = request.getHeaders();

        // 2. 提取并展开 X-Forwarded-For 代理链条
        List<String> xffHops = extractXffHops(headers);

        if (!xffHops.isEmpty()) {
            int totalHops = xffHops.size();
            // 从右往左倒推跳过可信代理：第 totalHops - trustedProxyCount 位即为可信代理所记录的真实客户端
            int targetIndex = totalHops - trustedProxyCount;
            if (targetIndex < 0) {
                targetIndex = 0;
            }
            String candidateIp = xffHops.get(targetIndex);
            if (StringUtils.hasText(candidateIp)) {
                return candidateIp;
            }
        }

        // 3. X-Forwarded-For 为空时，尝试回退提取 X-Real-IP
        String realIp = headers.getFirst(GatewayConstants.X_REAL_IP);
        if (StringUtils.hasText(realIp)) {
            String strippedRealIp = stripPort(realIp.trim());
            if (StringUtils.hasText(strippedRealIp)) {
                return strippedRealIp;
            }
        }

        // 4. 最终兜底物理对端 IP 或 127.0.0.1
        return StringUtils.hasText(directRemoteIp) ? directRemoteIp : DEFAULT_FALLBACK_IP;
    }

    /**
     * 提取请求的直接对端 IP
     */
    private static String extractDirectRemoteIp(ServerHttpRequest request) {
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return stripPort(remoteAddress.getAddress().getHostAddress());
        }
        return null;
    }

    /**
     * 提取并规范化解析 X-Forwarded-For 中的所有代理跳
     */
    private static List<String> extractXffHops(HttpHeaders headers) {
        List<String> hops = new ArrayList<>();
        List<String> xffHeaders = headers.get(GatewayConstants.X_FORWARDED_FOR);
        if (xffHeaders == null || xffHeaders.isEmpty()) {
            return hops;
        }

        for (String rawHeader : xffHeaders) {
            if (StringUtils.hasText(rawHeader)) {
                String[] segments = rawHeader.split(",");
                for (String segment : segments) {
                    String trimmed = segment.trim();
                    if (StringUtils.hasText(trimmed)) {
                        hops.add(stripPort(trimmed));
                    }
                }
            }
        }
        return hops;
    }

    /**
     * 去除 IP 字符串末尾携带的端口号（如 192.168.1.1:8080 或 [::1]:8080）
     */
    public static String stripPort(String ip) {
        if (!StringUtils.hasText(ip)) {
            return ip;
        }
        String trimmed = ip.trim();
        // 处理 IPv6 括号带端口格式 [::1]:8080
        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            int closeBracket = trimmed.indexOf(']');
            return trimmed.substring(1, closeBracket);
        }
        // 处理 IPv4 带端口格式 1.2.3.4:8080 (仅包含一个冒号且为非 IPv6)
        int colonIndex = trimmed.indexOf(':');
        if (colonIndex > 0 && trimmed.indexOf(':', colonIndex + 1) < 0) {
            return trimmed.substring(0, colonIndex);
        }
        return trimmed;
    }
}
