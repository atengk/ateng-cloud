package io.github.atengk.cloud.gateway.filter;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.reactor.spring.SaTokenContextForSpringReactor;
import cn.dev33.satoken.reactor.spring.SaTokenContextRegister;
import cn.dev33.satoken.same.SaSameUtil;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTenantProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sa-Token 网关集中鉴权与 Same-Token 开关单元测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
@DisplayName("SaTokenGatewayFilter 单元测试")
class SaTokenGatewayFilterTest {

    private SaTokenContext previousContext;

    @AfterEach
    void cleanupSaContext() {
        SaReactorSyncHolder.clearContext();
        SaManager.removeStpLogic("admin");
        SaManager.removeStpLogic("member");
        SaManager.removeStpLogic("vip");
        if (previousContext != null) {
            SaManager.setSaTokenContext(previousContext);
            previousContext = null;
        }
    }

    @Test
    @DisplayName("多账号路由映射支持 satoken-admin 与最具体路径优先且跨体系绝不回退")
    void testMultiAccountLoginTypeRoutingAndStrictIsolation() {
        setupRealReactorSaContext();
        AtengGatewaySecurityProperties properties = new AtengGatewaySecurityProperties();
        properties.setSameTokenEnabled(false);
        // 故意先写宽泛路径 /admin/**，再写更具体路径 /admin/vip/**，验证最具体模式优先仲裁
        Map<String, List<String>> mappings = new LinkedHashMap<>();
        mappings.put("admin", List.of("/admin/**"));
        mappings.put("vip", List.of("/admin/vip/**"));
        mappings.put("member", List.of("/app/**"));
        properties.setLoginTypeMappings(mappings);
        properties.setExcludePaths(List.of("/admin/public/**"));

        SaTokenGatewayFilter filter = new SaTokenGatewayFilter(properties);

        String adminToken = loginWithCustomLogic("admin", "9001", Map.of("tenantId", "t-admin"));
        String vipToken = loginWithCustomLogic("vip", "9002", Map.of("tenantId", "t-vip"));
        String defaultToken = loginWithDefaultStpUtil("1001", "t-default");

        // 1. /admin/users 命中 admin 体系，携带 satoken-admin 成功放行并注入身份
        ServerWebExchange adminExchange = executeFilterWithHeader(filter, "/admin/users", "satoken-admin", adminToken);
        assertEquals("9001", adminExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
        assertEquals("t-admin", adminExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
        assertEquals(Boolean.TRUE, adminExchange.getAttribute(GatewayConstants.ATTR_AUTHENTICATED));

        // 2. /admin/vip/orders 虽在 /admin/** 之后声明，仍因最具体模式优先命中 vip 体系
        ServerWebExchange vipExchange = executeFilterWithHeader(filter, "/admin/vip/orders", "satoken-vip", vipToken);
        assertEquals("9002", vipExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
        assertEquals("t-vip", vipExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));

        // 3. 携带默认 satoken 访问 /admin/users 绝不回退至 login 体系，严格 401 阻断
        ResponseStatusException unauthorized = assertThrows(ResponseStatusException.class,
                () -> executeFilterWithHeader(filter, "/admin/users", "satoken", defaultToken));
        assertEquals(HttpStatus.UNAUTHORIZED, unauthorized.getStatusCode());

        // 4. 白名单路径 /admin/public/info 同样按解析出的 admin 体系同步已登录上下文
        ServerWebExchange whitelistAuthExchange = executeFilterWithHeader(filter, "/admin/public/info", "satoken-admin", adminToken);
        assertEquals("9001", whitelistAuthExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
        assertEquals("t-admin", whitelistAuthExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
        assertEquals(Boolean.TRUE, whitelistAuthExchange.getAttribute(GatewayConstants.ATTR_AUTHENTICATED));

        // 5. 白名单路径未登录时正常放行且不注入已认证标记
        ServerWebExchange whitelistAnonExchange = executeFilter(filter, "/admin/public/info");
        assertNull(whitelistAnonExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
        assertNull(whitelistAnonExchange.getAttribute(GatewayConstants.ATTR_AUTHENTICATED));
    }

    @Test
    @DisplayName("兼容 AccountSession 与 TokenSession 的 login_user JSON、RunAs 模拟态与复合主键防误拆")
    void testLoginUserSessionExtractionAndCompositeLoginIdUnpacking() {
        setupRealReactorSaContext();
        AtengGatewaySecurityProperties properties = new AtengGatewaySecurityProperties();
        properties.setSameTokenEnabled(false);
        properties.setLoginTypeMappings(Map.of("admin", List.of("/admin/**"), "member", List.of("/app/**")));
        SaTokenGatewayFilter filter = new SaTokenGatewayFilter(properties);

        // 场景 A: Account Session 存 login_user JSON + RunAs 模拟态 (switch_target_user_id)
        String adminJson = "{\"userId\":\"1001\",\"tenantId\":\"000000\"}";
        String adminToken = loginWithCustomLogic("admin", "000000_1001", Map.of(
                "login_user", adminJson,
                "switch_target_user_id", "ops-999"
        ));
        ServerWebExchange runAsExchange = executeFilterWithHeader(filter, "/admin/orders", "satoken-admin", adminToken);
        assertEquals("ops-999", runAsExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
        assertEquals("000000", runAsExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));

        // 场景 B: TokenSession 兜底存 login_user JSON 且 userId 含下划线、无 tenantId 时不被拆解
        String memberJson = "{\"userId\":\"wx_2002\"}";
        String memberToken = loginWithTokenSession("member", "wx_2002", Map.of("login_user", memberJson));
        ServerWebExchange memberExchange = executeFilterWithHeader(filter, "/app/profile", "satoken-member", memberToken);
        assertEquals("wx_2002", memberExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
        assertNull(memberExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));

        // 场景 C: 无 login_user 载荷时按首个下划线解包复合 loginId (000000_usr_99 -> tenant=000000, user=usr_99)
        String compositeOnlyToken = loginWithDefaultStpUtil("000000_usr_99", null);
        ServerWebExchange compositeExchange = executeFilterWithHeader(filter, "/api/orders", "satoken", compositeOnlyToken);
        assertEquals("usr_99", compositeExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
        assertEquals("000000", compositeExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));

        // 场景 D: 禁用 compositeLoginIdEnabled 时保留完整下划线 ID 不拆解
        AtengGatewaySecurityProperties noSplitProps = new AtengGatewaySecurityProperties();
        noSplitProps.setSameTokenEnabled(false);
        noSplitProps.setCompositeLoginIdEnabled(false);
        SaTokenGatewayFilter noSplitFilter = new SaTokenGatewayFilter(noSplitProps);
        String rawUnderscoreToken = loginWithDefaultStpUtil("wx_8888", null);
        ServerWebExchange noSplitExchange = executeFilterWithHeader(noSplitFilter, "/api/orders", "satoken", rawUnderscoreToken);
        assertEquals("wx_8888", noSplitExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
        assertNull(noSplitExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));

        // 场景 E: 畸形 login_user JSON 安全降级至复合主键解包，不抛出 500
        String malformedJsonToken = loginWithDefaultStpUtil("t-safe_3003", null);
        MockServerWebExchange tempEx = MockServerWebExchange.from(MockServerHttpRequest.get("/tmp").header("satoken", malformedJsonToken).build());
        SaReactorSyncHolder.setContext(tempEx, () -> StpUtil.getSession().set("login_user", "{broken-json"));
        ServerWebExchange degradedExchange = executeFilterWithHeader(filter, "/api/orders", "satoken", malformedJsonToken);
        assertEquals("3003", degradedExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
        assertEquals("t-safe", degradedExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));

        // 场景 F: login_user 以 Map 对象形式存储在 TokenSession，且 TokenSession 携带 switch_target_user_id
        String mapToken = loginWithTokenSession("member", "4004", Map.of(
                "login_user", Map.of("userId", "4004", "tenantId", "t-map"),
                "switch_target_user_id", "proxy-4004"
        ));
        ServerWebExchange mapExchange = executeFilterWithHeader(filter, "/app/orders", "satoken-member", mapToken);
        assertEquals("proxy-4004", mapExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
        assertEquals("t-map", mapExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
    }

    @Test
    @DisplayName("JWT extra 租户标识优先级高于 Session 且支持默认体系回退")
    void testJwtExtraTenantPriority() {
        AtengGatewaySecurityProperties properties = new AtengGatewaySecurityProperties();
        properties.setSameTokenEnabled(false);
        SaTokenGatewayFilter filter = new SaTokenGatewayFilter(properties);

        try (MockedStatic<SaReactorSyncHolder> ctxMock = Mockito.mockStatic(SaReactorSyncHolder.class);
             MockedStatic<StpUtil> stpMock = Mockito.mockStatic(StpUtil.class)) {
            mockLoggedInUser(stpMock, "10086", "session-tenant");
            stpMock.when(() -> StpUtil.getExtra("tenantId")).thenReturn("jwt-extra-tenant");

            ServerWebExchange exchange = executeFilter(filter, "/api/orders");
            assertEquals("10086", exchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
            assertEquals("jwt-extra-tenant", exchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            ctxMock.verify(SaReactorSyncHolder::clearContext);
        }
    }

    @Test
    @DisplayName("开启 sameTokenEnabled(默认) 时向已登录请求注入 Same-Token 标头")
    void testSameTokenInjectedWhenEnabled() {
        AtengGatewaySecurityProperties properties = new AtengGatewaySecurityProperties();
        properties.setExcludePaths(List.of("/api/public/**"));
        SaTokenGatewayFilter filter = new SaTokenGatewayFilter(properties);

        try (MockedStatic<SaReactorSyncHolder> ctxMock = Mockito.mockStatic(SaReactorSyncHolder.class);
             MockedStatic<StpUtil> stpMock = Mockito.mockStatic(StpUtil.class);
             MockedStatic<SaSameUtil> sameMock = Mockito.mockStatic(SaSameUtil.class)) {
            mockLoggedInUser(stpMock, "10086", "t-100");
            sameMock.when(SaSameUtil::getToken).thenReturn("mock-same-token");

            ServerWebExchange downstream = executeFilter(filter, "/api/orders");

            assertNotNull(downstream);
            assertEquals("10086", downstream.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
            assertEquals("t-100", downstream.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            assertEquals("mock-same-token", downstream.getRequest().getHeaders().getFirst(GatewayConstants.SAME_TOKEN));
            assertEquals("mock-same-token", downstream.getRequest().getHeaders().getFirst(GatewayConstants.SAME_TOKEN_UPPER));
            assertEquals("mock-same-token", downstream.getRequest().getHeaders().getFirst(GatewayConstants.SA_SAME_TOKEN));
            ctxMock.verify(SaReactorSyncHolder::clearContext);
        }
    }

    @Test
    @DisplayName("开启 allowSuperAdminSwitch 时从 login_user 角色/权限与 StpLogic 识别超管身份")
    void testSuperAdminExtractionFromLoginUserAndStpLogic() {
        setupRealReactorSaContext();
        AtengGatewaySecurityProperties secProps = new AtengGatewaySecurityProperties();
        secProps.setSameTokenEnabled(false);
        secProps.setLoginTypeMappings(Map.of("admin", List.of("/admin/**")));
        AtengGatewayTenantProperties tenantProps = new AtengGatewayTenantProperties();
        tenantProps.setAllowSuperAdminSwitch(true);
        tenantProps.setSuperAdminRoles(List.of("super_admin"));
        tenantProps.setSuperAdminPermissions(List.of("*:*:*"));

        SaTokenGatewayFilter filter = new SaTokenGatewayFilter(secProps, tenantProps);

        String superToken = loginWithCustomLogic("admin", "0_1", Map.of(
                "login_user", "{\"userId\":\"1\",\"tenantId\":\"0\",\"roles\":[\"super_admin\"]}"
        ));
        ServerWebExchange superExchange = executeFilterWithHeader(filter, "/admin/tenants", "satoken-admin", superToken);
        assertEquals(Boolean.TRUE, superExchange.getAttribute(GatewayConstants.ATTR_SUPER_ADMIN));

        String normalToken = loginWithCustomLogic("admin", "0_2", Map.of(
                "login_user", "{\"userId\":\"2\",\"tenantId\":\"0\",\"roles\":[\"operator\"],\"permissions\":[\"order:list\"]}"
        ));
        ServerWebExchange normalExchange = executeFilterWithHeader(filter, "/admin/tenants", "satoken-admin", normalToken);
        assertEquals(Boolean.FALSE, normalExchange.getAttribute(GatewayConstants.ATTR_SUPER_ADMIN));

        String wildcardPermToken = loginWithTokenSession("admin", "0_3", Map.of(
                "login_user", Map.of("userId", "3", "tenantId", "0", "permissions", List.of("*:*:*"))
        ));
        ServerWebExchange permExchange = executeFilterWithHeader(filter, "/admin/tenants", "satoken-admin", wildcardPermToken);
        assertEquals(Boolean.TRUE, permExchange.getAttribute(GatewayConstants.ATTR_SUPER_ADMIN));
    }

    @Test
    @DisplayName("关闭 sameTokenEnabled 时跳过 Same-Token 注入且保留用户与租户上下文传递")
    void testSameTokenSkippedWhenDisabled() {
        AtengGatewaySecurityProperties properties = new AtengGatewaySecurityProperties();
        properties.setSameTokenEnabled(false);
        properties.setExcludePaths(List.of("/api/public/**"));
        SaTokenGatewayFilter filter = new SaTokenGatewayFilter(properties);

        try (MockedStatic<SaReactorSyncHolder> ctxMock = Mockito.mockStatic(SaReactorSyncHolder.class);
             MockedStatic<StpUtil> stpMock = Mockito.mockStatic(StpUtil.class);
             MockedStatic<SaSameUtil> sameMock = Mockito.mockStatic(SaSameUtil.class)) {
            stpMock.when(StpUtil::isLogin).thenReturn(false);

            ServerWebExchange whitelistExchange = executeFilter(filter, "/api/public/ping");
            assertNotNull(whitelistExchange);
            assertNull(whitelistExchange.getRequest().getHeaders().getFirst(GatewayConstants.SAME_TOKEN));
            assertNull(whitelistExchange.getRequest().getHeaders().getFirst(GatewayConstants.SAME_TOKEN_UPPER));
            assertNull(whitelistExchange.getRequest().getHeaders().getFirst(GatewayConstants.SA_SAME_TOKEN));

            mockLoggedInUser(stpMock, "10086", "t-100");

            ServerWebExchange authExchange = executeFilter(filter, "/api/orders");
            assertNotNull(authExchange);
            assertEquals("10086", authExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
            assertEquals("t-100", authExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            assertNull(authExchange.getRequest().getHeaders().getFirst(GatewayConstants.SAME_TOKEN));
            assertNull(authExchange.getRequest().getHeaders().getFirst(GatewayConstants.SAME_TOKEN_UPPER));
            assertNull(authExchange.getRequest().getHeaders().getFirst(GatewayConstants.SA_SAME_TOKEN));
            sameMock.verifyNoInteractions();
            ctxMock.verify(SaReactorSyncHolder::clearContext, Mockito.times(2));
        }
    }

    private void mockLoggedInUser(MockedStatic<StpUtil> stpMock, String userId, String tenantId) {
        stpMock.when(StpUtil::isLogin).thenReturn(true);
        stpMock.when(StpUtil::getLoginIdDefaultNull).thenReturn(userId);
        SaSession session = Mockito.mock(SaSession.class);
        Mockito.when(session.get("tenantId")).thenReturn(tenantId);
        stpMock.when(() -> StpUtil.getSession(false)).thenReturn(session);
    }

    private ServerWebExchange executeFilter(SaTokenGatewayFilter filter, String path) {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get(path).build()), chain).block();
        return captured.get();
    }

    private ServerWebExchange executeFilterWithHeader(SaTokenGatewayFilter filter, String path, String headerName, String headerVal) {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };
        MockServerHttpRequest request = MockServerHttpRequest.get(path)
                .header(headerName, headerVal)
                .build();
        filter.filter(MockServerWebExchange.from(request), chain).block();
        return captured.get();
    }

    private void setupRealReactorSaContext() {
        previousContext = SaManager.getSaTokenContext();
        new SaTokenContextRegister();
        SaManager.setSaTokenContext(new SaTokenContextForSpringReactor());
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
    }

    private String loginWithTokenSession(String loginType, String loginId, Map<String, Object> tokenSessionAttrs) {
        StpLogic logic = SaTokenGatewayFilter.getOrCreateStpLogic(loginType);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login").build());
        return SaReactorSyncHolder.setContext(exchange, () -> {
            logic.login(loginId);
            if (tokenSessionAttrs != null && !tokenSessionAttrs.isEmpty()) {
                SaSession tokenSession = logic.getTokenSession();
                tokenSessionAttrs.forEach(tokenSession::set);
            }
            return logic.getTokenValue();
        });
    }

    private String loginWithCustomLogic(String loginType, String loginId, Map<String, Object> sessionAttrs) {
        StpLogic logic = SaTokenGatewayFilter.getOrCreateStpLogic(loginType);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login").build());
        return SaReactorSyncHolder.setContext(exchange, () -> {
            logic.login(loginId);
            if (sessionAttrs != null && !sessionAttrs.isEmpty()) {
                SaSession session = logic.getSession();
                sessionAttrs.forEach(session::set);
            }
            return logic.getTokenValue();
        });
    }

    private String loginWithDefaultStpUtil(String loginId, String tenantId) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login").build());
        return SaReactorSyncHolder.setContext(exchange, () -> {
            StpUtil.login(loginId);
            if (tenantId != null) {
                StpUtil.getSession().set("tenantId", tenantId);
            }
            return StpUtil.getTokenValue();
        });
    }
}
