package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTenantProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 多租户入站解析过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("多租户过滤器单元测试")
class GatewayTenantFilterTest {

    @Test
    @DisplayName("已登录会话强制使用登录态绑定的真实 tenantId 覆盖入站头")
    void testAuthenticatedTenantOverride() {
        AtengGatewayTenantProperties properties = new AtengGatewayTenantProperties();
        GatewayTenantFilter tenantFilter = new GatewayTenantFilter(properties);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_TENANT_ID, "forged-tenant-999")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        // 模拟已登录态注入的真实租户 ID
        exchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, "real-tenant-100");

        GatewayFilterChain chain = ex -> {
            assertEquals("real-tenant-100", ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            return Mono.empty();
        };

        tenantFilter.filter(exchange, chain).block();
    }

    @Test
    @DisplayName("未登录接口且 allowHeader=false 时强制剥离入站伪造的 X-Tenant-Id")
    void testUnauthenticatedHeaderStrippedWhenNotAllowed() {
        AtengGatewayTenantProperties properties = new AtengGatewayTenantProperties();
        properties.setAllowHeader(false);
        GatewayTenantFilter tenantFilter = new GatewayTenantFilter(properties);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_TENANT_ID, "forged-tenant-999")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            assertNull(ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            return Mono.empty();
        };

        tenantFilter.filter(exchange, chain).block();
    }

    @Test
    @DisplayName("未登录接口且 allowHeader=true 时允许读取外部 X-Tenant-Id")
    void testUnauthenticatedHeaderAllowedWhenConfigured() {
        AtengGatewayTenantProperties properties = new AtengGatewayTenantProperties();
        properties.setAllowHeader(true);
        GatewayTenantFilter tenantFilter = new GatewayTenantFilter(properties);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_TENANT_ID, "public-tenant-200")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            assertEquals("public-tenant-200", ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            assertEquals("public-tenant-200", ex.getAttribute(GatewayConstants.ATTR_TENANT_ID));
            return Mono.empty();
        };

        tenantFilter.filter(exchange, chain).block();
    }
}
