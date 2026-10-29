package io.github.atengk.cloud.traffic.filter;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
import io.github.atengk.cloud.traffic.rule.model.TrafficCondition;
import io.github.atengk.cloud.traffic.rule.model.TrafficFieldSource;
import io.github.atengk.cloud.traffic.rule.model.TrafficMatchOperator;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 流量入站过滤器与全生命周期清理单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class TrafficContextFilterTest {

    private AtengTrafficProperties properties;
    private TrafficContextFilter filter;

    @BeforeEach
    void setUp() {
        properties = new AtengTrafficProperties();
        filter = new TrafficContextFilter(properties);
        TrafficContextHolder.clear();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        TrafficContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试从 HTTP 请求头提取流量标签并在链条内部染色")
    void testExtractTagFromHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader(TrafficConstants.HEADER_TRAFFIC_TAG, "header-canary-v1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> tagInsideChain = new AtomicReference<>();
        AtomicReference<String> mdcInsideChain = new AtomicReference<>();

        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                tagInsideChain.set(TrafficContextHolder.getTag());
                mdcInsideChain.set(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG));
            }
        };

        filter.doFilter(request, response, filterChain);

        // 验证过滤器执行中能够正确染色
        assertThat(tagInsideChain.get()).isEqualTo("header-canary-v1");
        assertThat(mdcInsideChain.get()).isEqualTo("header-canary-v1");

        // 验证执行出口 finally 块已彻底清理
        assertThat(TrafficContextHolder.getTag()).isNull();
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isNull();
    }

    @Test
    @DisplayName("测试从 URL Query 参数提取备选流量标签")
    void testExtractTagFromQueryParam() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.setParameter(TrafficConstants.PARAM_TRAFFIC_TAG, "param-canary-v2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> tagInsideChain = new AtomicReference<>();

        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                tagInsideChain.set(TrafficContextHolder.getTag());
            }
        };

        filter.doFilter(request, response, filterChain);

        assertThat(tagInsideChain.get()).isEqualTo("param-canary-v2");
        assertThat(TrafficContextHolder.getTag()).isNull();
    }

    @Test
    @DisplayName("测试 Header 优先级高于 Query 参数")
    void testHeaderPrecedenceOverQuery() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader(TrafficConstants.HEADER_TRAFFIC_TAG, "priority-header");
        request.setParameter(TrafficConstants.PARAM_TRAFFIC_TAG, "fallback-param");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> tagInsideChain = new AtomicReference<>();

        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                tagInsideChain.set(TrafficContextHolder.getTag());
            }
        };

        filter.doFilter(request, response, filterChain);

        assertThat(tagInsideChain.get()).isEqualTo("priority-header");
        assertThat(TrafficContextHolder.getTag()).isNull();
    }

    @Test
    @DisplayName("测试当功能禁用时不执行染色")
    void testDisabledFilter() throws ServletException, IOException {
        properties.setEnabled(false);
        filter = new TrafficContextFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader(TrafficConstants.HEADER_TRAFFIC_TAG, "any-tag");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> tagInsideChain = new AtomicReference<>();

        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                tagInsideChain.set(TrafficContextHolder.getTag());
            }
        };

        filter.doFilter(request, response, filterChain);

        assertThat(tagInsideChain.get()).isNull();
    }

    @Test
    @DisplayName("测试当下游业务抛出异常时 finally 块依然可靠执行清理")
    void testCleanupOnException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader(TrafficConstants.HEADER_TRAFFIC_TAG, "exception-tag");
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("Business error during request processing");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Business error");

        // 强断言：异常抛出后，线程上下文与 MDC 依然被无条件清理
        assertThat(TrafficContextHolder.getTag()).isNull();
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isNull();
    }

    @Test
    @DisplayName("测试通过动态规则决策引擎为无标签请求自动染色")
    void testDynamicRuleMatchingStaining() throws ServletException, IOException {
        TrafficRule rule = TrafficRule.builder()
                .id("rule-device-ios")
                .targetTag("canary-ios")
                .conditions(java.util.List.of(
                        TrafficCondition.builder()
                                .fieldSource(TrafficFieldSource.HEADER)
                                .fieldName("X-Device-Type")
                                .operator(TrafficMatchOperator.EXACT)
                                .expectedValue("iOS")
                                .build()
                ))
                .build();

        TrafficRuleProvider provider = () -> java.util.List.of(rule);
        TrafficRuleMatcher matcher = new TrafficRuleMatcher();
        TrafficContextFilter filterWithRules = new TrafficContextFilter(properties, matcher, provider);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader("X-Device-Type", "iOS");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> tagInsideChain = new AtomicReference<>();
        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                tagInsideChain.set(TrafficContextHolder.getTag());
            }
        };

        filterWithRules.doFilter(request, response, filterChain);

        assertThat(tagInsideChain.get()).isEqualTo("canary-ios");
        assertThat(TrafficContextHolder.getTag()).isNull();
    }

    @Test
    @DisplayName("测试显式 Header 标签优先级高于动态规则匹配")
    void testExplicitHeaderPrecedenceOverDynamicRules() throws ServletException, IOException {
        TrafficRule rule = TrafficRule.builder()
                .id("rule-device-ios")
                .targetTag("canary-ios")
                .conditions(java.util.List.of(
                        TrafficCondition.builder()
                                .fieldSource(TrafficFieldSource.HEADER)
                                .fieldName("X-Device-Type")
                                .operator(TrafficMatchOperator.EXACT)
                                .expectedValue("iOS")
                                .build()
                ))
                .build();

        TrafficRuleProvider provider = () -> java.util.List.of(rule);
        TrafficRuleMatcher matcher = new TrafficRuleMatcher();
        TrafficContextFilter filterWithRules = new TrafficContextFilter(properties, matcher, provider);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader(TrafficConstants.HEADER_TRAFFIC_TAG, "explicit-override-tag");
        request.addHeader("X-Device-Type", "iOS");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> tagInsideChain = new AtomicReference<>();
        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                tagInsideChain.set(TrafficContextHolder.getTag());
            }
        };

        filterWithRules.doFilter(request, response, filterChain);

        assertThat(tagInsideChain.get()).isEqualTo("explicit-override-tag");
    }
}
