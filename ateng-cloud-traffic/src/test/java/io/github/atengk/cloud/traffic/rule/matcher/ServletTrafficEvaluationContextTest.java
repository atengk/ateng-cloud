package io.github.atengk.cloud.traffic.rule.matcher;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Servlet 请求评估上下文适配器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class ServletTrafficEvaluationContextTest {

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("从 HttpServletRequest 正确提取各种属性测试")
    void testExtractFromRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Custom-Tag", "v2-canary");
        request.addParameter("query_id", "12345");
        request.setCookies(new Cookie("session_id", "sess_abcdef"));
        request.setRequestURI("/api/v1/users");
        request.setRemoteAddr("10.0.0.1");

        ServletTrafficEvaluationContext context = ServletTrafficEvaluationContext.from(request);

        assertThat(context.getHeader("X-Custom-Tag")).isEqualTo("v2-canary");
        assertThat(context.getHeader("Non-Existent")).isNull();

        assertThat(context.getQueryParam("query_id")).isEqualTo("12345");
        assertThat(context.getQueryParam("non_param")).isNull();

        assertThat(context.getCookie("session_id")).isEqualTo("sess_abcdef");
        assertThat(context.getCookie("SESSION_ID")).isEqualTo("sess_abcdef"); // 忽略大小写
        assertThat(context.getCookie("other_cookie")).isNull();

        assertThat(context.getPath()).isEqualTo("/api/v1/users");
        assertThat(context.getClientIp()).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("客户端真实 IP 代理穿透提取测试 (X-Forwarded-For 与 X-Real-IP)")
    void testClientIpForwardedHeaders() {
        // 1. X-Forwarded-For 多级代理提取首个真实 IP
        MockHttpServletRequest request1 = new MockHttpServletRequest();
        request1.addHeader("X-Forwarded-For", "203.0.113.195, 150.172.238.178");
        request1.setRemoteAddr("10.0.0.1");
        ServletTrafficEvaluationContext context1 = ServletTrafficEvaluationContext.from(request1);
        assertThat(context1.getClientIp()).isEqualTo("203.0.113.195");

        // 2. X-Real-IP 单一真实代理 IP
        MockHttpServletRequest request2 = new MockHttpServletRequest();
        request2.addHeader("X-Real-IP", "198.51.100.1");
        request2.setRemoteAddr("10.0.0.1");
        ServletTrafficEvaluationContext context2 = ServletTrafficEvaluationContext.from(request2);
        assertThat(context2.getClientIp()).isEqualTo("198.51.100.1");
    }

    @Test
    @DisplayName("UserContextHolder 与 TenantContextHolder 兜底透传测试")
    void testUserAndTenantContextFallback() {
        UserContextHolder.setUserId("user_mock_007");
        TenantContextHolder.setTenantId("tenant_mock_sh");

        MockHttpServletRequest request = new MockHttpServletRequest();
        ServletTrafficEvaluationContext context = ServletTrafficEvaluationContext.from(request);

        assertThat(context.getUserId()).isEqualTo("user_mock_007");
        assertThat(context.getTenantId()).isEqualTo("tenant_mock_sh");
    }

    @Test
    @DisplayName("null 请求防御测试")
    void testNullRequest() {
        ServletTrafficEvaluationContext context = ServletTrafficEvaluationContext.from(null);
        assertThat(context.getHeader("any")).isNull();
        assertThat(context.getQueryParam("any")).isNull();
        assertThat(context.getCookie("any")).isNull();
        assertThat(context.getPath()).isNull();
        assertThat(context.getClientIp()).isNull();
    }
}
