package io.github.atengk.cloud.traffic.reactive;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.metrics.TrafficMetricsManager;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.properties.TrafficWhitelistAction;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
import io.github.atengk.cloud.traffic.rule.model.TrafficCondition;
import io.github.atengk.cloud.traffic.rule.model.TrafficFieldSource;
import io.github.atengk.cloud.traffic.rule.model.TrafficMatchOperator;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebFlux 响应式入站流量染色过滤器单元测试
 *
 * @author Ateng
 * @since 2026-11-13
 */
class ReactiveTrafficContextWebFilterTest {

    private AtengTrafficProperties properties;
    private TrafficMetricsManager metricsManager;

    @BeforeEach
    void setUp() {
        properties = new AtengTrafficProperties();
        metricsManager = new TrafficMetricsManager(null, null);
    }

    @Test
    @DisplayName("测试 Header、Query 与动态规则提取成功后双通道绑定请求头与 Reactor Context")
    void testHeaderQueryAndDynamicRuleDualChannelBinding() {
        TrafficRule rule = TrafficRule.builder()
                .id("reactive-rule-vip")
                .priority(1)
                .targetTag("v2-reactive")
                .enabled(true)
                .conditions(List.of(
                        TrafficCondition.builder()
                                .fieldSource(TrafficFieldSource.HEADER)
                                .fieldName("X-Channel")
                                .operator(TrafficMatchOperator.EXACT)
                                .expectedValue("vip")
                                .build()
                ))
                .build();
        TrafficRuleProvider provider = () -> List.of(rule);
        ReactiveTrafficContextWebFilter webFilter = new ReactiveTrafficContextWebFilter(
                properties, new TrafficRuleMatcher(), provider, metricsManager);
        assertThat(webFilter.getOrder()).isEqualTo(properties.getFilterOrder());

        // 1. 动态规则匹配 -> 注入下游 ServerHttpRequest Header + Reactor Context
        MockServerWebExchange ruleExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/orders").header("X-Channel", "vip").build());
        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        AtomicReference<TrafficContext> downstreamReactiveCtx = new AtomicReference<>();
        WebFilterChain chain = ex -> Mono.deferContextual(ctx -> {
            downstreamHeader.set(ex.getRequest().getHeaders().getFirst(TrafficConstants.HEADER_TRAFFIC_TAG));
            downstreamReactiveCtx.set(ctx.getOrDefault(TrafficContext.class, null));
            return Mono.empty();
        });

        webFilter.filter(ruleExchange, chain).block();
        assertThat(downstreamHeader.get()).isEqualTo("v2-reactive");
        assertThat(downstreamReactiveCtx.get()).isNotNull();
        assertThat(downstreamReactiveCtx.get().getTag()).isEqualTo("v2-reactive");
        assertThat(downstreamReactiveCtx.get().getMatchedRule()).isEqualTo("reactive-rule-vip");

        // 2. Query 参数兜底提取
        downstreamHeader.set(null);
        downstreamReactiveCtx.set(null);
        MockServerWebExchange queryExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/orders?traffic_tag=v3-query").build());
        webFilter.filter(queryExchange, chain).block();
        assertThat(downstreamHeader.get()).isEqualTo("v3-query");
        assertThat(downstreamReactiveCtx.get().getTag()).isEqualTo("v3-query");
    }

    @Test
    @DisplayName("测试白名单 STRIP 模式清洗伪造标头与基线归一化，不写入 Reactor Context")
    void testWhitelistStripAndBaselineNormalization() {
        properties.setAllowedTags(Set.of("v2"));
        properties.setWhitelistAction(TrafficWhitelistAction.STRIP);
        ReactiveTrafficContextWebFilter webFilter = new ReactiveTrafficContextWebFilter(
                properties, null, null, metricsManager);

        // 1. 未授权标签被 STRIP 并从下游请求头清除
        MockServerWebExchange rogueExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/orders")
                        .header(TrafficConstants.HEADER_TRAFFIC_TAG, "forged-lane")
                        .build());
        AtomicReference<Boolean> hasHeaderInChain = new AtomicReference<>(true);
        AtomicReference<TrafficContext> ctxInChain = new AtomicReference<>();
        WebFilterChain chain = ex -> Mono.deferContextual(ctx -> {
            hasHeaderInChain.set(ex.getRequest().getHeaders().containsKey(TrafficConstants.HEADER_TRAFFIC_TAG));
            ctxInChain.set(ctx.getOrDefault(TrafficContext.class, null));
            return Mono.empty();
        });

        webFilter.filter(rogueExchange, chain).block();
        assertThat(hasHeaderInChain.get()).isFalse();
        assertThat(ctxInChain.get()).isNull();
        assertThat(metricsManager.getStatusCount(TrafficMetricsManager.STATUS_WHITELIST_STRIPPED)).isEqualTo(1L);

        // 2. 基线保留字 (baseline) 即使在 REJECT 模式下亦归一化为基线并清理请求头
        properties.setWhitelistAction(TrafficWhitelistAction.REJECT);
        MockServerWebExchange baselineExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/orders")
                        .header(TrafficConstants.HEADER_TRAFFIC_TAG, "baseline")
                        .build());
        hasHeaderInChain.set(true);
        webFilter.filter(baselineExchange, chain).block();
        assertThat(hasHeaderInChain.get()).isFalse();
        assertThat(ctxInChain.get()).isNull();
    }

    @Test
    @DisplayName("测试白名单 REJECT 模式阻断非法标签并写出包含 4212 错误码的 UTF-8 JSON 响应")
    void testWhitelistRejectReturns4212Json() {
        properties.setAllowedTags(Set.of("v2"));
        properties.setWhitelistAction(TrafficWhitelistAction.REJECT);
        ReactiveTrafficContextWebFilter webFilter = new ReactiveTrafficContextWebFilter(
                properties, null, null, metricsManager);

        MockServerWebExchange rejectExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/orders")
                        .header(TrafficConstants.HEADER_TRAFFIC_TAG, "evil-reactive-tag")
                        .build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        webFilter.filter(rejectExchange, chain).block();

        assertThat(chainCalled.get()).isFalse();
        assertThat(metricsManager.getStatusCount(TrafficMetricsManager.STATUS_WHITELIST_REJECTED)).isEqualTo(1L);
        String body = rejectExchange.getResponse().getBodyAsString().block();
        assertThat(body).isNotBlank();
        assertThat(new String(body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .contains("4212")
                .contains("evil-reactive-tag");
    }
}
