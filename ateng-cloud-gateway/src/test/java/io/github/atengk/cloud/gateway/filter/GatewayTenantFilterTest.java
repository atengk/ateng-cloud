package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTenantProperties;
import io.github.atengk.cloud.gateway.spi.GatewayTenantSwitchValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    @DisplayName("已登录但无租户绑定时即便 allowHeader=true 也严禁采信入站 X-Tenant-Id")
    void testAuthenticatedWithoutTenantNeverTrustsIncomingHeader() {
        AtengGatewayTenantProperties properties = new AtengGatewayTenantProperties();
        properties.setAllowHeader(true);
        GatewayTenantFilter tenantFilter = new GatewayTenantFilter(properties);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_TENANT_ID, "forged-tenant-999")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayConstants.ATTR_AUTHENTICATED, Boolean.TRUE);
        exchange.getAttributes().put(GatewayConstants.ATTR_USER_ID, "platform-admin-1");

        GatewayFilterChain chain = ex -> {
            assertNull(ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            assertNull(ex.getAttribute(GatewayConstants.ATTR_TENANT_ID));
            return Mono.empty();
        };

        tenantFilter.filter(exchange, chain).block();
    }

    @Test
    @DisplayName("默认关闭 allowSuperAdminSwitch 时即便具备超管标识仍强制锁定会话租户")
    void testSuperAdminSwitchDisabledByDefault() {
        AtengGatewayTenantProperties properties = new AtengGatewayTenantProperties();
        GatewayTenantFilter tenantFilter = new GatewayTenantFilter(properties);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test").build());
        exchange.getAttributes().put(GatewayConstants.ATTR_AUTHENTICATED, Boolean.TRUE);
        exchange.getAttributes().put(GatewayConstants.ATTR_USER_ID, "1");
        exchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, "0");
        exchange.getAttributes().put(GatewayConstants.ATTR_SUPER_ADMIN, Boolean.TRUE);
        exchange.getAttributes().put(GatewayConstants.ATTR_REQUESTED_TENANT_ID, "tenant-target");

        tenantFilter.filter(exchange, ex -> {
            assertEquals("0", ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            assertEquals("0", ex.getAttribute(GatewayConstants.ATTR_TENANT_ID));
            return Mono.empty();
        }).block();
    }

    @Test
    @DisplayName("开启 allowSuperAdminSwitch 时平台超管放行合法切换、普通用户与非平台租户超管及匿名流量全被阻断锁定")
    void testSuperAdminTenantSwitchThreeDefenses() {
        AtengGatewayTenantProperties properties = new AtengGatewayTenantProperties();
        properties.setAllowHeader(false);
        properties.setAllowSuperAdminSwitch(true);
        properties.setPlatformTenantIds(List.of("0"));
        GatewayTenantFilter tenantFilter = new GatewayTenantFilter(properties);

        // 1. 匿名请求即便携带 ATTR_REQUESTED_TENANT_ID 也绝不放行
        MockServerWebExchange anonExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/public").header(GatewayConstants.X_TENANT_ID, "tenant-target").build());
        anonExchange.getAttributes().put(GatewayConstants.ATTR_REQUESTED_TENANT_ID, "tenant-target");
        tenantFilter.filter(anonExchange, ex -> {
            assertNull(ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            assertNull(ex.getAttribute(GatewayConstants.ATTR_TENANT_ID));
            return Mono.empty();
        }).block();

        // 2. 普通已登录用户强制锁定自身会话租户
        MockServerWebExchange normalExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders").build());
        normalExchange.getAttributes().put(GatewayConstants.ATTR_AUTHENTICATED, Boolean.TRUE);
        normalExchange.getAttributes().put(GatewayConstants.ATTR_USER_ID, "100");
        normalExchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, "0");
        normalExchange.getAttributes().put(GatewayConstants.ATTR_SUPER_ADMIN, Boolean.FALSE);
        normalExchange.getAttributes().put(GatewayConstants.ATTR_REQUESTED_TENANT_ID, "tenant-target");
        tenantFilter.filter(normalExchange, ex -> {
            assertEquals("0", ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            return Mono.empty();
        }).block();

        // 3. 非平台租户的超管禁止切租户
        MockServerWebExchange nonPlatformExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders").build());
        nonPlatformExchange.getAttributes().put(GatewayConstants.ATTR_AUTHENTICATED, Boolean.TRUE);
        nonPlatformExchange.getAttributes().put(GatewayConstants.ATTR_USER_ID, "101");
        nonPlatformExchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, "tenant-other");
        nonPlatformExchange.getAttributes().put(GatewayConstants.ATTR_SUPER_ADMIN, Boolean.TRUE);
        nonPlatformExchange.getAttributes().put(GatewayConstants.ATTR_REQUESTED_TENANT_ID, "tenant-target");
        tenantFilter.filter(nonPlatformExchange, ex -> {
            assertEquals("tenant-other", ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            return Mono.empty();
        }).block();

        // 4. 平台租户 + 超管携带合法目标租户成功切换
        MockServerWebExchange superExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders").build());
        superExchange.getAttributes().put(GatewayConstants.ATTR_AUTHENTICATED, Boolean.TRUE);
        superExchange.getAttributes().put(GatewayConstants.ATTR_USER_ID, "1");
        superExchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, "0");
        superExchange.getAttributes().put(GatewayConstants.ATTR_SUPER_ADMIN, Boolean.TRUE);
        superExchange.getAttributes().put(GatewayConstants.ATTR_REQUESTED_TENANT_ID, "tenant-target");
        tenantFilter.filter(superExchange, ex -> {
            assertEquals("tenant-target", ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            assertEquals("tenant-target", ex.getAttribute(GatewayConstants.ATTR_TENANT_ID));
            return Mono.empty();
        }).block();
    }

    @Test
    @DisplayName("超管切换非法格式租户直接 400 Fail-Fast，且受响应式 GatewayTenantSwitchValidator SPI 约束")
    void testSuperAdminTenantSwitchFormatAndSpiValidation() {
        AtengGatewayTenantProperties properties = new AtengGatewayTenantProperties();
        properties.setAllowSuperAdminSwitch(true);

        GatewayTenantSwitchValidator rejectValidator = (ex, target, session, uid) ->
                Mono.just("tenant-allowed".equals(target));
        GatewayTenantFilter tenantFilter = new GatewayTenantFilter(properties, rejectValidator);

        // 1. 非法格式 -> 400
        MockServerWebExchange invalidExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders").build());
        invalidExchange.getAttributes().put(GatewayConstants.ATTR_AUTHENTICATED, Boolean.TRUE);
        invalidExchange.getAttributes().put(GatewayConstants.ATTR_USER_ID, "1");
        invalidExchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, "0");
        invalidExchange.getAttributes().put(GatewayConstants.ATTR_SUPER_ADMIN, Boolean.TRUE);
        invalidExchange.getAttributes().put(GatewayConstants.ATTR_REQUESTED_TENANT_ID, "../evil");

        BusinessException badFormat = assertThrows(BusinessException.class,
                () -> tenantFilter.filter(invalidExchange, ex -> Mono.empty()).block());
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), badFormat.getCode());

        // 2. SPI 拒绝 -> 403
        MockServerWebExchange forbiddenExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders").build());
        forbiddenExchange.getAttributes().put(GatewayConstants.ATTR_AUTHENTICATED, Boolean.TRUE);
        forbiddenExchange.getAttributes().put(GatewayConstants.ATTR_USER_ID, "1");
        forbiddenExchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, "0");
        forbiddenExchange.getAttributes().put(GatewayConstants.ATTR_SUPER_ADMIN, Boolean.TRUE);
        forbiddenExchange.getAttributes().put(GatewayConstants.ATTR_REQUESTED_TENANT_ID, "tenant-forbidden");

        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> tenantFilter.filter(forbiddenExchange, ex -> Mono.empty()).block());
        assertEquals(CommonResultCode.FORBIDDEN.getCode(), forbidden.getCode());

        // 3. SPI 放行 -> 切换成功
        MockServerWebExchange allowedExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders").build());
        allowedExchange.getAttributes().put(GatewayConstants.ATTR_AUTHENTICATED, Boolean.TRUE);
        allowedExchange.getAttributes().put(GatewayConstants.ATTR_USER_ID, "1");
        allowedExchange.getAttributes().put(GatewayConstants.ATTR_TENANT_ID, "0");
        allowedExchange.getAttributes().put(GatewayConstants.ATTR_SUPER_ADMIN, Boolean.TRUE);
        allowedExchange.getAttributes().put(GatewayConstants.ATTR_REQUESTED_TENANT_ID, "tenant-allowed");

        tenantFilter.filter(allowedExchange, ex -> {
            assertEquals("tenant-allowed", ex.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            return Mono.empty();
        }).block();
    }
}
