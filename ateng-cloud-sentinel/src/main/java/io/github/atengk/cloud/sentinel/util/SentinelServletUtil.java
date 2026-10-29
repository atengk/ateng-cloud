package io.github.atengk.cloud.sentinel.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Sentinel Servlet 运行时请求辅助工具类
 *
 * @author Ateng
 * @since 2026-10-29
 */
public final class SentinelServletUtil {

    private SentinelServletUtil() {
    }

    /**
     * 解析客户端真实 IP 地址
     * <p>依次尝试按 {@code X-Forwarded-For} (逗号多 IP 截取第一项)、{@code X-Real-IP} 与 {@link HttpServletRequest#getRemoteAddr()} 回溯解析</p>
     *
     * @param request HTTP 请求对象
     * @return 客户端真实 IP，无法解析时保底返回 unknown
     */
    public static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr.trim() : "unknown";
    }
}
