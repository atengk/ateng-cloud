package io.github.atengk.cloud.traffic.rule.matcher;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 基于 Jakarta Servlet HttpServletRequest 的流量规则评估上下文适配器
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class ServletTrafficEvaluationContext implements TrafficEvaluationContext {

    private final HttpServletRequest request;

    public ServletTrafficEvaluationContext(HttpServletRequest request) {
        this.request = request;
    }

    public static ServletTrafficEvaluationContext from(HttpServletRequest request) {
        return new ServletTrafficEvaluationContext(request);
    }

    @Override
    public String getHeader(String name) {
        if (request == null || name == null) {
            return null;
        }
        return request.getHeader(name);
    }

    @Override
    public String getQueryParam(String name) {
        if (request == null || name == null) {
            return null;
        }
        return request.getParameter(name);
    }

    @Override
    public String getCookie(String name) {
        if (request == null || name == null) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equalsIgnoreCase(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    @Override
    public String getPath() {
        return request != null ? request.getRequestURI() : null;
    }

    @Override
    public String getUserId() {
        return UserContextHolder.getUserId();
    }

    @Override
    public String getTenantId() {
        return TenantContextHolder.getTenantId();
    }

    @Override
    public String getClientIp() {
        if (request == null) {
            return null;
        }
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            int index = xForwardedFor.indexOf(',');
            return index != -1 ? xForwardedFor.substring(0, index).trim() : xForwardedFor.trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
