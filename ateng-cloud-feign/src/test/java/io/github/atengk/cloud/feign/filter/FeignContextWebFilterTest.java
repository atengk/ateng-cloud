package io.github.atengk.cloud.feign.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.feign.constant.FeignHeaderConstants;
import io.github.atengk.cloud.feign.properties.AtengFeignContextProperties;
import io.github.atengk.cloud.feign.spi.SameTokenProvider;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.filter.TrafficContextFilter;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.properties.TrafficWhitelistAction;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficEvaluationContext;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServlet;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feign 提供端入站上下文还原与确定性清理过滤器单元测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
@DisplayName("Feign 提供端入站上下文还原与确定性清理过滤器单元测试")
class FeignContextWebFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        if (RootContext.inGlobalTransaction()) {
            RootContext.unbind();
        }
        TrafficContextHolder.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. 验证入站请求自动还原四维上下文、灰度泳道与 Seata XID，并在请求结束后彻底清理")
    void testInboundRestoresAllContextsAndClearsAfterCompletion() throws Exception {
        AtengFeignContextProperties properties = new AtengFeignContextProperties();
        FeignContextWebFilter filter = new FeignContextWebFilter(properties, null, objectMapper);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders/create");
        request.addHeader(FeignHeaderConstants.TRACE_ID, "trace-inbound-1001");
        request.addHeader(FeignHeaderConstants.USER_ID, "user-inbound-88");
        request.addHeader(FeignHeaderConstants.TENANT_ID, "tenant-inbound-99");
        request.addHeader(FeignHeaderConstants.IGNORE_TENANT, "true");
        request.addHeader(FeignHeaderConstants.TRAFFIC_TAG, "canary-v2");
        request.addHeader(FeignHeaderConstants.TRAFFIC_LANE, "lane-order");
        request.addHeader(FeignHeaderConstants.TRAFFIC_RULE, "rule-header");
        request.addHeader(FeignHeaderConstants.TX_XID, "192.168.10.1:8091:777888");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenTraceId = new AtomicReference<>();
        AtomicReference<String> seenUserId = new AtomicReference<>();
        AtomicReference<String> seenTenantId = new AtomicReference<>();
        AtomicReference<Boolean> seenIgnoreTenant = new AtomicReference<>();
        AtomicReference<TrafficContext> seenTraffic = new AtomicReference<>();
        AtomicReference<String> seenXid = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            seenTraceId.set(MDC.get(FeignHeaderConstants.MDC_TRACE_ID));
            seenUserId.set(UserContextHolder.getUserId());
            seenTenantId.set(TenantContextHolder.getTenantId());
            seenIgnoreTenant.set(TenantContextHolder.isIgnoreTenant());
            seenTraffic.set(TrafficContextHolder.getContext());
            seenXid.set(RootContext.getXID());
        };

        filter.doFilter(request, response, chain);

        // 验证下游业务逻辑执行期间可见全部上下文
        assertThat(seenTraceId.get()).isEqualTo("trace-inbound-1001");
        assertThat(seenUserId.get()).isEqualTo("user-inbound-88");
        assertThat(seenTenantId.get()).isEqualTo("tenant-inbound-99");
        assertThat(seenIgnoreTenant.get()).isTrue();
        assertThat(seenTraffic.get()).isNotNull();
        assertThat(seenTraffic.get().getTag()).isEqualTo("canary-v2");
        assertThat(seenTraffic.get().getLaneName()).isEqualTo("lane-order");
        assertThat(seenTraffic.get().getMatchedRule()).isEqualTo("rule-header");
        assertThat(seenXid.get()).isEqualTo("192.168.10.1:8091:777888");

        // 验证请求结束后 finally 100% 清理干净
        assertThat(MDC.get(FeignHeaderConstants.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(FeignHeaderConstants.TRACE_ID)).isNull();
        assertThat(UserContextHolder.getExplicit()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
        assertThat(TrafficContextHolder.getContext()).isNull();
        assertThat(RootContext.getXID()).isNull();
        assertThat(MDC.get(FeignHeaderConstants.MDC_XID)).isNull();
    }

    @Test
    @DisplayName("2. 验证下游业务抛出异常时 finally 仍确定性清理全部上下文，同时过滤脏 null 标头")
    void testFinallyCleanupOnExceptionAndSanitizeNullHeaders() {
        AtengFeignContextProperties properties = new AtengFeignContextProperties();
        FeignContextWebFilter filter = new FeignContextWebFilter(properties, null, objectMapper);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/fail");
        request.addHeader(FeignHeaderConstants.USER_ID, "user-error-case");
        request.addHeader(FeignHeaderConstants.TENANT_ID, "null");
        request.addHeader(FeignHeaderConstants.TRAFFIC_TAG, "canary-err");
        request.addHeader(FeignHeaderConstants.TX_XID, "10.0.0.1:8091:555666");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain throwingChain = (req, res) -> {
            assertThat(UserContextHolder.getUserId()).isEqualTo("user-error-case");
            assertThat(TenantContextHolder.getTenantId()).isNull();
            assertThat(TrafficContextHolder.getTag()).isEqualTo("canary-err");
            assertThat(RootContext.getXID()).isEqualTo("10.0.0.1:8091:555666");
            throw new IllegalStateException("模拟下游业务异常");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
                .isInstanceOf(IllegalStateException.class);

        // 即使业务抛异常，所有线程变量仍必须彻底清理
        assertThat(UserContextHolder.getExplicit()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(TrafficContextHolder.getContext()).isNull();
        assertThat(RootContext.getXID()).isNull();
        assertThat(MDC.get(FeignHeaderConstants.MDC_XID)).isNull();
    }

    @Test
    @DisplayName("3. 验证入站前存在外层上下文快照时，退出 finally 100% 恢复至入站前快照")
    void testRestoresPreExistingSnapshotOnExit() throws Exception {
        MDC.put(FeignHeaderConstants.MDC_TRACE_ID, "outer-trace");
        UserContextHolder.setUserId("outer-user");
        TenantContextHolder.setTenantId("outer-tenant");
        TenantContextHolder.setIgnoreTenant(true);
        TrafficContextHolder.setContext(TrafficContext.of("outer-tag", "outer-lane", "outer-rule"));
        RootContext.bind("outer-xid");
        MDC.put(FeignHeaderConstants.MDC_XID, "outer-xid");

        AtengFeignContextProperties properties = new AtengFeignContextProperties();
        FeignContextWebFilter filter = new FeignContextWebFilter(properties, null, objectMapper);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/nested");
        request.addHeader(FeignHeaderConstants.TRACE_ID, "inner-trace");
        request.addHeader(FeignHeaderConstants.USER_ID, "inner-user");
        request.addHeader(FeignHeaderConstants.TENANT_ID, "inner-tenant");
        request.addHeader(FeignHeaderConstants.TRAFFIC_TAG, "inner-tag");
        request.addHeader(FeignHeaderConstants.TRAFFIC_LANE, "inner-lane");
        request.addHeader(FeignHeaderConstants.TX_XID, "inner-xid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(FeignHeaderConstants.MDC_TRACE_ID)).isEqualTo("inner-trace");
            assertThat(UserContextHolder.getUserId()).isEqualTo("inner-user");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("inner-tenant");
            assertThat(TrafficContextHolder.getTag()).isEqualTo("inner-tag");
            // 若当前线程已有不同 RootContext XID，不覆盖既有全局事务防止事务撕裂，同时 MDC xid 与外层事务保持一致
            assertThat(RootContext.getXID()).isEqualTo("outer-xid");
            assertThat(MDC.get(FeignHeaderConstants.MDC_XID)).isEqualTo("outer-xid");
        });

        // 退出后必须完整恢复外层既有上下文快照
        assertThat(MDC.get(FeignHeaderConstants.MDC_TRACE_ID)).isEqualTo("outer-trace");
        assertThat(UserContextHolder.getExplicit()).isEqualTo("outer-user");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("outer-tenant");
        assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();
        assertThat(TrafficContextHolder.getContext()).isNotNull();
        assertThat(TrafficContextHolder.getContext().getTag()).isEqualTo("outer-tag");
        assertThat(TrafficContextHolder.getContext().getLaneName()).isEqualTo("outer-lane");
        assertThat(TrafficContextHolder.getContext().getMatchedRule()).isEqualTo("outer-rule");
        assertThat(RootContext.getXID()).isEqualTo("outer-xid");
        assertThat(MDC.get(FeignHeaderConstants.MDC_XID)).isEqualTo("outer-xid");
    }

    @Test
    @DisplayName("4. 验证开启 check-same-token 时，缺失或无效 Same-Token 立即返回 HTTP 401 与 UNAUTHORIZED Result JSON")
    void testCheckSameTokenRejectsMissingOrInvalidToken() throws Exception {
        AtengFeignContextProperties properties = new AtengFeignContextProperties();
        properties.setCheckSameToken(true);
        SameTokenProvider provider = () -> "expected-internal-secret-token";
        FeignContextWebFilter filter = new FeignContextWebFilter(properties, provider, objectMapper);

        // 4.1 缺失 Same-Token
        MockHttpServletRequest missingReq = new MockHttpServletRequest("GET", "/api/orders/detail");
        MockHttpServletResponse missingResp = new MockHttpServletResponse();
        AtomicBoolean missingChainExecuted = new AtomicBoolean(false);
        filter.doFilter(missingReq, missingResp, (req, res) -> missingChainExecuted.set(true));

        assertThat(missingChainExecuted.get()).isFalse();
        assertThat(missingResp.getStatus()).isEqualTo(401);
        var missingJson = objectMapper.readTree(missingResp.getContentAsString(StandardCharsets.UTF_8));
        assertThat(missingJson.get("code").asInt()).isEqualTo(401);
        assertThat(missingJson.get("msg").asText()).contains("Same-Token");

        // 4.2 携带伪造 Same-Token
        MockHttpServletRequest invalidReq = new MockHttpServletRequest("POST", "/api/orders/create");
        invalidReq.addHeader(FeignHeaderConstants.SAME_TOKEN, "forged-token");
        MockHttpServletResponse invalidResp = new MockHttpServletResponse();
        AtomicBoolean invalidChainExecuted = new AtomicBoolean(false);
        filter.doFilter(invalidReq, invalidResp, (req, res) -> invalidChainExecuted.set(true));

        assertThat(invalidChainExecuted.get()).isFalse();
        assertThat(invalidResp.getStatus()).isEqualTo(401);
        var invalidJson = objectMapper.readTree(invalidResp.getContentAsString(StandardCharsets.UTF_8));
        assertThat(invalidJson.get("code").asInt()).isEqualTo(401);

        // 4.3 开启校验但未装配 SameTokenProvider 时安全失败拒绝 (Fail-Closed)
        FeignContextWebFilter failClosedFilter = new FeignContextWebFilter(properties, null, objectMapper);
        MockHttpServletRequest noProviderReq = new MockHttpServletRequest("GET", "/api/orders/detail");
        noProviderReq.addHeader(FeignHeaderConstants.SAME_TOKEN, "any-token");
        MockHttpServletResponse noProviderResp = new MockHttpServletResponse();
        failClosedFilter.doFilter(noProviderReq, noProviderResp, (req, res) -> {});
        assertThat(noProviderResp.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("5. 验证合法 Same-Token 正常放行并还原上下文，且 exclude-paths 白名单路径免校验放行")
    void testValidSameTokenAndExcludePathsPass() throws Exception {
        AtengFeignContextProperties properties = new AtengFeignContextProperties();
        properties.setCheckSameToken(true);
        properties.setExcludePaths(List.of("/actuator/**", "/health/check"));
        SameTokenProvider provider = () -> "valid-same-token-2026";
        FeignContextWebFilter filter = new FeignContextWebFilter(properties, provider, objectMapper);

        // 5.1 携带合法 Same-Token 正常放行且还原用户上下文
        MockHttpServletRequest validReq = new MockHttpServletRequest("GET", "/api/orders/1");
        validReq.addHeader(FeignHeaderConstants.SAME_TOKEN, "valid-same-token-2026");
        validReq.addHeader(FeignHeaderConstants.USER_ID, "trusted-caller");
        MockHttpServletResponse validResp = new MockHttpServletResponse();
        AtomicReference<String> observedUser = new AtomicReference<>();
        filter.doFilter(validReq, validResp, (req, res) -> observedUser.set(UserContextHolder.getUserId()));

        assertThat(validResp.getStatus()).isEqualTo(200);
        assertThat(observedUser.get()).isEqualTo("trusted-caller");
        assertThat(UserContextHolder.getExplicit()).isNull();

        // 5.2 命中 exclude-paths 白名单（含 contextPath 场景）直接免校验放行
        MockHttpServletRequest actuatorReq = new MockHttpServletRequest("GET", "/order-svc/actuator/health");
        actuatorReq.setContextPath("/order-svc");
        actuatorReq.setServletPath("/actuator/health");
        MockHttpServletResponse actuatorResp = new MockHttpServletResponse();
        AtomicBoolean actuatorPassed = new AtomicBoolean(false);
        filter.doFilter(actuatorReq, actuatorResp, (req, res) -> actuatorPassed.set(true));

        assertThat(actuatorPassed.get()).isTrue();
        assertThat(actuatorResp.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("6. 验证入站还原新标签时清洗旧 MDC 泳道/规则残留，并在退出 finally 完整恢复原始快照")
    void testCleansStaleTrafficMdcKeysAndRestoresPreviousMdc() throws Exception {
        MDC.put(FeignHeaderConstants.TRAFFIC_TAG, "stale-tag");
        MDC.put(FeignHeaderConstants.TRAFFIC_LANE, "stale-lane");
        MDC.put(FeignHeaderConstants.MDC_TRAFFIC_LANE, "stale-lane");
        MDC.put(FeignHeaderConstants.TRAFFIC_RULE, "stale-rule");
        MDC.put(FeignHeaderConstants.MDC_TRAFFIC_RULE, "stale-rule");

        FeignContextWebFilter filter = new FeignContextWebFilter(new AtengFeignContextProperties(), null, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader(FeignHeaderConstants.TRAFFIC_TAG, "fresh-tag");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(TrafficContextHolder.getTag()).isEqualTo("fresh-tag");
            assertThat(TrafficContextHolder.getContext().getLaneName()).isNull();
            assertThat(TrafficContextHolder.getContext().getMatchedRule()).isNull();
            assertThat(MDC.get(FeignHeaderConstants.TRAFFIC_LANE)).isNull();
            assertThat(MDC.get(FeignHeaderConstants.MDC_TRAFFIC_LANE)).isNull();
            assertThat(MDC.get(FeignHeaderConstants.TRAFFIC_RULE)).isNull();
            assertThat(MDC.get(FeignHeaderConstants.MDC_TRAFFIC_RULE)).isNull();
        });

        assertThat(MDC.get(FeignHeaderConstants.TRAFFIC_TAG)).isEqualTo("stale-tag");
        assertThat(MDC.get(FeignHeaderConstants.TRAFFIC_LANE)).isEqualTo("stale-lane");
        assertThat(MDC.get(FeignHeaderConstants.TRAFFIC_RULE)).isEqualTo("stale-rule");
    }

    @Test
    @DisplayName("7. 验证与前置 TrafficContextFilter 协同：绝不重新激活已被 STRIP 剥离的非法标签，且保留动态规则与干净清理 MDC")
    void testCooperationWithTrafficContextFilterRespectsStripAndDynamicRule() throws Exception {
        AtengTrafficProperties trafficProperties = new AtengTrafficProperties();
        trafficProperties.setAllowedTags(Set.of("canary"));
        trafficProperties.setWhitelistAction(TrafficWhitelistAction.STRIP);
        TrafficContextFilter stripTrafficFilter = new TrafficContextFilter(trafficProperties);
        FeignContextWebFilter feignFilter = new FeignContextWebFilter(new AtengFeignContextProperties(), null, objectMapper);

        // 7.1 非法标签被 TrafficContextFilter STRIP 剥离后，FeignContextWebFilter 严禁重新激活
        MockHttpServletRequest strippedReq = new MockHttpServletRequest("GET", "/api/orders");
        strippedReq.addHeader(FeignHeaderConstants.TRAFFIC_TAG, "rogue-tag");
        MockHttpServletResponse strippedResp = new MockHttpServletResponse();
        AtomicReference<TrafficContext> strippedSeen = new AtomicReference<>(TrafficContext.of("sentinel"));

        MockFilterChain stripChain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void doGet(jakarta.servlet.http.HttpServletRequest req,
                                 jakarta.servlet.http.HttpServletResponse resp) {
                strippedSeen.set(TrafficContextHolder.getContext());
            }
        }, stripTrafficFilter, feignFilter);
        stripChain.doFilter(strippedReq, strippedResp);

        assertThat(strippedSeen.get()).isNull();
        assertThat(TrafficContextHolder.getContext()).isNull();
        assertThat(MDC.get(FeignHeaderConstants.TRAFFIC_TAG)).isNull();

        // 7.2 前置 TrafficContextFilter 动态规则命中 rule-dyn 时，携带 X-Traffic-Lane 不覆写 matchedRule 且退出无 MDC 残留
        TrafficRule rule = new TrafficRule();
        rule.setId("rule-dyn");
        rule.setTargetTag("canary");
        TrafficRuleMatcher ruleMatcher = new TrafficRuleMatcher() {
            @Override
            public TrafficRule matchRule(List<TrafficRule> rules, TrafficEvaluationContext context) {
                return rule;
            }
        };
        TrafficRuleProvider ruleProvider = () -> List.of(rule);
        TrafficContextFilter dynamicTrafficFilter = new TrafficContextFilter(trafficProperties, ruleMatcher, ruleProvider);

        MockHttpServletRequest dynReq = new MockHttpServletRequest("GET", "/api/orders");
        dynReq.addHeader(FeignHeaderConstants.TRAFFIC_LANE, "lane-order");
        MockHttpServletResponse dynResp = new MockHttpServletResponse();
        AtomicReference<TrafficContext> dynSeen = new AtomicReference<>();

        MockFilterChain dynamicChain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void doGet(jakarta.servlet.http.HttpServletRequest req,
                                 jakarta.servlet.http.HttpServletResponse resp) {
                dynSeen.set(TrafficContextHolder.getContext());
            }
        }, dynamicTrafficFilter, feignFilter);
        dynamicChain.doFilter(dynReq, dynResp);

        assertThat(dynSeen.get()).isNotNull();
        assertThat(dynSeen.get().getTag()).isEqualTo("canary");
        assertThat(dynSeen.get().getLaneName()).isEqualTo("lane-order");
        assertThat(dynSeen.get().getMatchedRule()).isEqualTo("rule-dyn");
        assertThat(TrafficContextHolder.getContext()).isNull();
        assertThat(MDC.get(FeignHeaderConstants.MDC_TRAFFIC_TAG)).isNull();
        assertThat(MDC.get(FeignHeaderConstants.TRAFFIC_LANE)).isNull();
        assertThat(MDC.get(FeignHeaderConstants.TRAFFIC_RULE)).isNull();
    }

    @Test
    @DisplayName("8. 验证开启 check-same-token 时阻断路径穿越/矩阵绕过白名单，并支持 servletPath+pathInfo 拼接与默认白名单")
    void testPathTraversalBypassBlockedAndServletPathInfoResolved() throws Exception {
        AtengFeignContextProperties properties = new AtengFeignContextProperties();
        properties.setCheckSameToken(true);
        FeignContextWebFilter filter = new FeignContextWebFilter(properties, () -> "trusted-secret", objectMapper);

        for (String maliciousUri : List.of(
                "/actuator/../api/orders/delete",
                "/actuator/%2e%2e/api/orders/delete",
                "/actuator%2f..%2fapi/orders",
                "/actuator;bypass=1/health",
                "/actuator\\..\\api/orders",
                "/actuator/%zz/health"
        )) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", maliciousUri);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            AtomicBoolean passed = new AtomicBoolean(false);
            filter.doFilter(req, resp, (r, s) -> passed.set(true));

            assertThat(passed.get()).as("Malicious path should be blocked: %s", maliciousUri).isFalse();
            assertThat(resp.getStatus()).as("Malicious path should return 401: %s", maliciousUri).isEqualTo(401);
        }

        // 前缀映射 servletPath + pathInfo：/actuator + /health 命中默认白名单放行；/api + /orders 未携带令牌被拦截
        MockHttpServletRequest actuatorPrefixed = new MockHttpServletRequest("GET", "/actuator/health");
        actuatorPrefixed.setServletPath("/actuator");
        actuatorPrefixed.setPathInfo("/health");
        MockHttpServletResponse actuatorPrefixedResp = new MockHttpServletResponse();
        AtomicBoolean actuatorPrefixedPassed = new AtomicBoolean(false);
        filter.doFilter(actuatorPrefixed, actuatorPrefixedResp, (r, s) -> actuatorPrefixedPassed.set(true));
        assertThat(actuatorPrefixedPassed.get()).isTrue();

        for (String defaultExcluded : List.of("/error", "/favicon.ico")) {
            MockHttpServletRequest excludedReq = new MockHttpServletRequest("GET", defaultExcluded);
            MockHttpServletResponse excludedResp = new MockHttpServletResponse();
            AtomicBoolean passed = new AtomicBoolean(false);
            filter.doFilter(excludedReq, excludedResp, (r, s) -> passed.set(true));
            assertThat(passed.get()).isTrue();
        }

        MockHttpServletRequest apiPrefixed = new MockHttpServletRequest("GET", "/api/orders/1");
        apiPrefixed.setServletPath("/api");
        apiPrefixed.setPathInfo("/orders/1");
        MockHttpServletResponse apiPrefixedResp = new MockHttpServletResponse();
        filter.doFilter(apiPrefixed, apiPrefixedResp, (r, s) -> {});
        assertThat(apiPrefixedResp.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("9. 验证 inboundEnabled=false 且 checkSameToken=true、大小写与别名标头、校验抛异常 Fail-Closed")
    void testInboundDisabledCheckSameTokenAliasAndExceptionFailClosed() throws Exception {
        AtengFeignContextProperties properties = new AtengFeignContextProperties();
        properties.setInboundEnabled(false);
        properties.setCheckSameToken(true);
        SameTokenProvider provider = () -> "secret-2026";
        FeignContextWebFilter filter = new FeignContextWebFilter(properties, provider, objectMapper);

        // 9.1 inboundEnabled=false 时无令牌仍返回 401
        MockHttpServletResponse deniedResp = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/orders"), deniedResp, (r, s) -> {});
        assertThat(deniedResp.getStatus()).isEqualTo(401);

        // 9.2 携带小写 same-token 或别名 SA-SAME-TOKEN 放行，且绝不还原入站上下文
        for (String headerName : List.of("same-token", FeignHeaderConstants.SA_SAME_TOKEN)) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders");
            req.addHeader(headerName, "secret-2026");
            req.addHeader(FeignHeaderConstants.USER_ID, "should-not-restore");
            req.addHeader(FeignHeaderConstants.TRACE_ID, "trace-should-not-restore");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            AtomicBoolean chainCalled = new AtomicBoolean(false);
            filter.doFilter(req, resp, (r, s) -> {
                chainCalled.set(true);
                assertThat(UserContextHolder.getExplicit()).isNull();
                assertThat(MDC.get(FeignHeaderConstants.MDC_TRACE_ID)).isNull();
            });
            assertThat(chainCalled.get()).isTrue();
            assertThat(resp.getStatus()).isEqualTo(200);
        }

        // 9.3 SameTokenProvider 抛出运行时异常时 Fail-Closed 返回 401
        SameTokenProvider throwingProvider = () -> {
            throw new IllegalStateException("Sa-Token Redis unavailable");
        };
        FeignContextWebFilter failClosedFilter = new FeignContextWebFilter(properties, throwingProvider, objectMapper);
        MockHttpServletRequest errReq = new MockHttpServletRequest("GET", "/api/orders");
        errReq.addHeader(FeignHeaderConstants.SAME_TOKEN, "any-token");
        MockHttpServletResponse errResp = new MockHttpServletResponse();
        failClosedFilter.doFilter(errReq, errResp, (r, s) -> {});
        assertThat(errResp.getStatus()).isEqualTo(401);
    }
}
