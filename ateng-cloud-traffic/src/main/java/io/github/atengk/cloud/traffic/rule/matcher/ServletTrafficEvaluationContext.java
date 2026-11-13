package io.github.atengk.cloud.traffic.rule.matcher;

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
        return TrafficEvaluationContext.resolveUserId(
                request != null ? request.getHeader("X-User-Id") : null,
                request != null ? request.getParameter("userId") : null);
    }

    @Override
    public String getTenantId() {
        return TrafficEvaluationContext.resolveTenantId(
                request != null ? request.getHeader("X-Tenant-Id") : null,
                request != null ? request.getParameter("tenantId") : null);
    }

    @Override
    public String getClientIp() {
        if (request == null) {
            return null;
        }
        return TrafficEvaluationContext.resolveClientIp(
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr());
    }
}
