package io.github.atengk.cloud.feign.interceptor;

import feign.Request;
import feign.RequestTemplate;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.feign.constant.FeignHeaderConstants;
import io.github.atengk.cloud.feign.spi.SameTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign 出站全链路四维上下文透传拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("Feign 出站全链路四维上下文透传拦截器单元测试")
class FeignContextPropagationInterceptorTest {

    @AfterEach
    void tearDown() {
        // 铁律：测试结束后彻底清理上下文，防止线程污染
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("测试完整上下文与 Same-Token 正常注入")
    void testPropagateAllContext() {
        MDC.put("traceId", "trace-test-1001");
        MDC.put("X-Traffic-Tag", "gray-v2");
        UserContextHolder.setUserId("user-8888");
        TenantContextHolder.setTenantId("tenant-6666");
        TenantContextHolder.setIgnoreTenant(true);

        SameTokenProvider mockProvider = () -> "same-token-val-9999";
        FeignContextPropagationInterceptor interceptor = new FeignContextPropagationInterceptor(mockProvider);

        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.GET);
        template.target("http://demo-service/test");

        interceptor.apply(template);

        assertThat(getHeader(template, FeignHeaderConstants.TRACE_ID)).isEqualTo("trace-test-1001");
        assertThat(getHeader(template, FeignHeaderConstants.USER_ID)).isEqualTo("user-8888");
        assertThat(getHeader(template, FeignHeaderConstants.TENANT_ID)).isEqualTo("tenant-6666");
        assertThat(getHeader(template, FeignHeaderConstants.IGNORE_TENANT)).isEqualTo("true");
        assertThat(getHeader(template, FeignHeaderConstants.TRAFFIC_TAG)).isEqualTo("gray-v2");
        assertThat(getHeader(template, FeignHeaderConstants.SAME_TOKEN)).isEqualTo("same-token-val-9999");
    }

    @Test
    @DisplayName("测试空上下文与空值防呆清洗 (绝不注入 null 字符串)")
    void testEmptyContextAndNullHygiene() {
        // 未设置任何上下文与 Provider
        FeignContextPropagationInterceptor interceptor = new FeignContextPropagationInterceptor(null);

        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.GET);
        template.target("http://demo-service/test");

        // 模拟外部意外写入了 "null" 脏字面量
        template.header("X-Dirty-Null", "null");
        template.header("X-Valid-Header", "hello");

        interceptor.apply(template);

        // 验证未注入项完全不存在
        assertThat(template.headers()).doesNotContainKey(FeignHeaderConstants.TRACE_ID);
        assertThat(template.headers()).doesNotContainKey(FeignHeaderConstants.USER_ID);
        assertThat(template.headers()).doesNotContainKey(FeignHeaderConstants.TENANT_ID);
        assertThat(template.headers()).doesNotContainKey(FeignHeaderConstants.IGNORE_TENANT);
        assertThat(template.headers()).doesNotContainKey(FeignHeaderConstants.TRAFFIC_TAG);
        assertThat(template.headers()).doesNotContainKey(FeignHeaderConstants.SAME_TOKEN);

        // 验证脏 "null" 标头被防御性清洗移除
        assertThat(template.headers()).doesNotContainKey("X-Dirty-Null");
        // 验证正常标头保留
        assertThat(getHeader(template, "X-Valid-Header")).isEqualTo("hello");
    }

    @Test
    @DisplayName("测试调用方显式设定的标头优先 (不被覆盖)")
    void testExistingHeadersNotOverwritten() {
        MDC.put("traceId", "trace-from-mdc");
        UserContextHolder.setUserId("user-from-context");

        FeignContextPropagationInterceptor interceptor = new FeignContextPropagationInterceptor(null);

        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.GET);
        template.target("http://demo-service/test");
        template.header(FeignHeaderConstants.TRACE_ID, "trace-explicit");
        template.header(FeignHeaderConstants.USER_ID, "user-explicit");

        interceptor.apply(template);

        assertThat(getHeader(template, FeignHeaderConstants.TRACE_ID)).isEqualTo("trace-explicit");
        assertThat(getHeader(template, FeignHeaderConstants.USER_ID)).isEqualTo("user-explicit");
    }

    @Test
    @DisplayName("测试拦截器实现 Ordered 接口且具有最高优先级 HIGHEST_PRECEDENCE")
    void testOrderedHighestPrecedence() {
        FeignContextPropagationInterceptor interceptor = new FeignContextPropagationInterceptor(null);
        assertThat(interceptor).isInstanceOf(org.springframework.core.Ordered.class);
        assertThat(((org.springframework.core.Ordered) interceptor).getOrder())
                .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
    }

    private String getHeader(RequestTemplate template, String headerName) {
        Collection<String> values = template.headers().get(headerName);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.iterator().next();
    }
}
