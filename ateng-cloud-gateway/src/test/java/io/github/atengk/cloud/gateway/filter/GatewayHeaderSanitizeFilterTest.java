package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityProperties;
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
 * 敏感标头清洗过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("敏感标头清洗过滤器单元测试")
class GatewayHeaderSanitizeFilterTest {

    @Test
    @DisplayName("默认情况下物理剥离所有内部敏感标头 (X-User-Id, X-Tenant-Id, Same-Token)")
    void testDefaultSanitizeHeaders() {
        AtengGatewaySecurityProperties securityProperties = new AtengGatewaySecurityProperties();
        GatewayHeaderSanitizeFilter filter = new GatewayHeaderSanitizeFilter(securityProperties);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_USER_ID, "forged-user-1")
                .header(GatewayConstants.X_TENANT_ID, "forged-tenant-1")
                .header(GatewayConstants.X_IGNORE_TENANT, "true")
                .header(GatewayConstants.SAME_TOKEN, "forged-token-1")
                .header("Custom-Header", "keep-me")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            assertNull(ex.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
            assertNull(ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            assertNull(ex.getRequest().getHeaders().getFirst(GatewayConstants.X_IGNORE_TENANT));
            assertNull(ex.getRequest().getHeaders().getFirst(GatewayConstants.SAME_TOKEN));
            assertEquals("keep-me", ex.getRequest().getHeaders().getFirst("Custom-Header"));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
    }

    @Test
    @DisplayName("当 tenantProperties.allowHeader=true 时放行 X-Tenant-Id 供下游租户过滤器消费")
    void testAllowTenantHeaderWhenConfigured() {
        AtengGatewaySecurityProperties securityProperties = new AtengGatewaySecurityProperties();
        AtengGatewayTenantProperties tenantProperties = new AtengGatewayTenantProperties();
        tenantProperties.setAllowHeader(true);

        GatewayHeaderSanitizeFilter filter = new GatewayHeaderSanitizeFilter(securityProperties, tenantProperties);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_USER_ID, "forged-user-1")
                .header(GatewayConstants.X_TENANT_ID, "public-tenant-100")
                .header(GatewayConstants.SAME_TOKEN, "forged-token-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            assertNull(ex.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
            assertEquals("public-tenant-100", ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            assertNull(ex.getRequest().getHeaders().getFirst(GatewayConstants.SAME_TOKEN));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
    }
}
