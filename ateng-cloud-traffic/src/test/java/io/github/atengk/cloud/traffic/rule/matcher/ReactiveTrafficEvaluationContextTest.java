package io.github.atengk.cloud.traffic.rule.matcher;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 响应式流量规则评估上下文适配器单元测试
 *
 * @author Ateng
 * @since 2026-11-13
 */
class ReactiveTrafficEvaluationContextTest {

    @BeforeEach
    void setUp() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("提取 WebFlux 请求 Header、Query、Cookie、Path 与客户端 IP")
    void testBasicRequestExtraction() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/orders?channel=vip&userId=q-user-1&tenantId=q-tenant-1")
                .header("X-Env", "canary")
                .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
                .cookie(new HttpCookie("RegionCookie", "shanghai"))
                .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                .build();

        ReactiveTrafficEvaluationContext context = ReactiveTrafficEvaluationContext.from(request);

        assertThat(context.getHeader("X-Env")).isEqualTo("canary");
        assertThat(context.getQueryParam("channel")).isEqualTo("vip");
        assertThat(context.getCookie("regioncookie")).isEqualTo("shanghai");
        assertThat(context.getPath()).isEqualTo("/api/v1/orders");
        assertThat(context.getUserId()).isEqualTo("q-user-1");
        assertThat(context.getTenantId()).isEqualTo("q-tenant-1");
        assertThat(context.getClientIp()).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("User/Tenant 优先回退顺序 (上下文Holder > Header > Query) 与 null 安全防卫")
    void testUserTenantPrecedenceAndNullSafety() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/items?userId=q-user&tenantId=q-tenant")
                .header("X-User-Id", "h-user")
                .header("X-Tenant-Id", "h-tenant")
                .header("X-Real-IP", "198.51.100.22")
                .build();

        ReactiveTrafficEvaluationContext context = ReactiveTrafficEvaluationContext.from(request);
        assertThat(context.getUserId()).isEqualTo("h-user");
        assertThat(context.getTenantId()).isEqualTo("h-tenant");
        assertThat(context.getClientIp()).isEqualTo("198.51.100.22");

        UserContextHolder.setUserId("ctx-user");
        TenantContextHolder.setTenantId("ctx-tenant");
        assertThat(context.getUserId()).isEqualTo("ctx-user");
        assertThat(context.getTenantId()).isEqualTo("ctx-tenant");

        UserContextHolder.clear();
        TenantContextHolder.clear();

        ReactiveTrafficEvaluationContext nullContext = ReactiveTrafficEvaluationContext.from(null);
        assertThat(nullContext.getHeader("X-Env")).isNull();
        assertThat(nullContext.getQueryParam("p")).isNull();
        assertThat(nullContext.getCookie("c")).isNull();
        assertThat(nullContext.getPath()).isNull();
        assertThat(nullContext.getUserId()).isNull();
        assertThat(nullContext.getTenantId()).isNull();
        assertThat(nullContext.getClientIp()).isNull();
    }
}
