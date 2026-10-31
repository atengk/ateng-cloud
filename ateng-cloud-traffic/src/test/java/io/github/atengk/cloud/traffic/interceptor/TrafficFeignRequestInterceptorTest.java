package io.github.atengk.cloud.traffic.interceptor;

import feign.Request;
import feign.RequestTemplate;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.Ordered;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Feign 出站流量染色标头透传拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class TrafficFeignRequestInterceptorTest {

    private AtengTrafficProperties properties;
    private TrafficFeignRequestInterceptor interceptor;

    @BeforeEach
    void setUp() {
        TrafficContextHolder.clear();
        MDC.clear();
        properties = new AtengTrafficProperties();
        interceptor = new TrafficFeignRequestInterceptor(properties);
    }

    @AfterEach
    void tearDown() {
        TrafficContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试当线程上下文包含有效流量标签时，安全注入 X-Traffic-Tag 标头")
    void testApply_WithValidTrafficTag() {
        TrafficContextHolder.setTag("gray-v1");
        RequestTemplate template = createRequestTemplate();

        interceptor.apply(template);

        Collection<String> headers = template.headers().get(TrafficConstants.HEADER_TRAFFIC_TAG);
        assertThat(headers).isNotNull().containsExactly("gray-v1");
    }

    @Test
    @DisplayName("测试当 TTL 为空但 MDC 存在流量标签时，MDC 兜底读取并注入标头")
    void testApply_WithMdcTrafficTag() {
        MDC.put(TrafficConstants.MDC_TRAFFIC_TAG, "canary-v2");
        RequestTemplate template = createRequestTemplate();

        interceptor.apply(template);

        Collection<String> headers = template.headers().get(TrafficConstants.HEADER_TRAFFIC_TAG);
        assertThat(headers).isNotNull().containsExactly("canary-v2");
    }

    @Test
    @DisplayName("测试当流量标签为空时，不予注入标头")
    void testApply_WithNullOrBlankTag() {
        RequestTemplate template = createRequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey(TrafficConstants.HEADER_TRAFFIC_TAG);
    }

    @Test
    @DisplayName("测试当流量标签为 'null' 脏字符串时，防御性拦截不予注入")
    void testApply_WithDirtyNullLiteralTag() {
        TrafficContextHolder.setTag("null");
        RequestTemplate template = createRequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey(TrafficConstants.HEADER_TRAFFIC_TAG);
    }

    @Test
    @DisplayName("测试当请求模板已显式设置同名有效 Header 时，恪守显式契约不予覆盖")
    void testApply_WithExistingValidHeader_ShouldNotOverwrite() {
        TrafficContextHolder.setTag("context-tag");
        RequestTemplate template = createRequestTemplate();
        template.header(TrafficConstants.HEADER_TRAFFIC_TAG, "explicit-tag");

        interceptor.apply(template);

        Collection<String> headers = template.headers().get(TrafficConstants.HEADER_TRAFFIC_TAG);
        assertThat(headers).isNotNull().containsExactly("explicit-tag");
    }

    @Test
    @DisplayName("测试当请求模板包含 'null' 脏 Header 时，自动清洗并注入上下文真实标签")
    void testApply_WithExistingDirtyHeader_ShouldSanitizeAndInjectValid() {
        TrafficContextHolder.setTag("valid-tag");
        RequestTemplate template = createRequestTemplate();
        template.header(TrafficConstants.HEADER_TRAFFIC_TAG, "null");

        interceptor.apply(template);

        Collection<String> headers = template.headers().get(TrafficConstants.HEADER_TRAFFIC_TAG);
        assertThat(headers).isNotNull().containsExactly("valid-tag");
    }

    @Test
    @DisplayName("测试当流量治理总开关关闭时，不注入标头")
    void testApply_WhenDisabled_ShouldNotInject() {
        properties.setEnabled(false);
        TrafficContextHolder.setTag("gray-v1");
        RequestTemplate template = createRequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey(TrafficConstants.HEADER_TRAFFIC_TAG);
    }

    @Test
    @DisplayName("测试当跨跳泳道穿透开关关闭时，不注入标头")
    void testApply_WhenLaneContinuityDisabled_ShouldNotInject() {
        properties.setLaneContinuity(false);
        TrafficContextHolder.setTag("gray-v1");
        RequestTemplate template = createRequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey(TrafficConstants.HEADER_TRAFFIC_TAG);
    }

    @Test
    @DisplayName("测试自定义流量染色标头名称透传")
    void testApply_WithCustomHeaderName() {
        properties.setHeaderName("X-Custom-Traffic-Lane");
        TrafficContextHolder.setTag("lane-beta");
        RequestTemplate template = createRequestTemplate();

        interceptor.apply(template);

        Collection<String> headers = template.headers().get("X-Custom-Traffic-Lane");
        assertThat(headers).isNotNull().containsExactly("lane-beta");
        assertThat(template.headers()).doesNotContainKey(TrafficConstants.HEADER_TRAFFIC_TAG);
    }

    @Test
    @DisplayName("测试拦截器执行顺序为最高优先级 HIGHEST_PRECEDENCE")
    void testOrder_IsHighestPrecedence() {
        assertThat(interceptor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("测试传入空 RequestTemplate 时安全防御不抛异常")
    void testApply_WithNullTemplate_ShouldNotThrow() {
        TrafficContextHolder.setTag("gray-v1");
        assertThatCode(() -> interceptor.apply(null)).doesNotThrowAnyException();
    }

    private RequestTemplate createRequestTemplate() {
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.GET);
        template.target("http://user-service/api/users");
        return template;
    }
}
