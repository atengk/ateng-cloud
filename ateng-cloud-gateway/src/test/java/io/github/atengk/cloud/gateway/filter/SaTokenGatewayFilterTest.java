package io.github.atengk.cloud.gateway.filter;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.same.SaSameUtil;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sa-Token 网关集中鉴权与 Same-Token 开关单元测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
@DisplayName("SaTokenGatewayFilter 单元测试")
class SaTokenGatewayFilterTest {

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
            ctxMock.verify(SaReactorSyncHolder::clearContext);
        }
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

            mockLoggedInUser(stpMock, "10086", "t-100");

            ServerWebExchange authExchange = executeFilter(filter, "/api/orders");
            assertNotNull(authExchange);
            assertEquals("10086", authExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_USER_ID));
            assertEquals("t-100", authExchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TENANT_ID));
            assertNull(authExchange.getRequest().getHeaders().getFirst(GatewayConstants.SAME_TOKEN));
            assertNull(authExchange.getRequest().getHeaders().getFirst(GatewayConstants.SAME_TOKEN_UPPER));
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
}
