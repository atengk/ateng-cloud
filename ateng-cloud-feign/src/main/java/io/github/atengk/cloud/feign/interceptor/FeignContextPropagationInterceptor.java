package io.github.atengk.cloud.feign.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.feign.constant.FeignHeaderConstants;
import io.github.atengk.cloud.feign.spi.SameTokenProvider;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Feign 出站全链路四维上下文透传与零信任防穿透请求拦截器
 * <p>
 * 自动从当前请求线程提取 MDC traceId、userId、tenantId、ignoreTenant、trafficTag 及 Same-Token，
 * 并安全注入出站 HTTP 标头。内置严格空安全清洗防线，杜绝向标头写入 "null" 或空白字符。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class FeignContextPropagationInterceptor implements RequestInterceptor {

    private final SameTokenProvider sameTokenProvider;

    public FeignContextPropagationInterceptor() {
        this(null);
    }

    public FeignContextPropagationInterceptor(SameTokenProvider sameTokenProvider) {
        this.sameTokenProvider = sameTokenProvider;
    }

    @Override
    public void apply(RequestTemplate template) {
        // 1. 提取并透传分布式链路 Trace ID
        String traceId = resolveTraceId();
        addHeaderIfValid(template, FeignHeaderConstants.TRACE_ID, traceId);

        // 2. 提取并透传当前登录用户 ID
        String userId = UserContextHolder.getUserId();
        addHeaderIfValid(template, FeignHeaderConstants.USER_ID, userId);

        // 3. 提取并透传当前多租户 ID
        String tenantId = TenantContextHolder.getTenantId();
        addHeaderIfValid(template, FeignHeaderConstants.TENANT_ID, tenantId);

        // 4. 提取并透传忽略租户标记 (仅当为 true 时显式透传，杜绝传递 false 或空值)
        if (TenantContextHolder.isIgnoreTenant()) {
            addHeaderIfValid(template, FeignHeaderConstants.IGNORE_TENANT, Boolean.TRUE.toString());
        }

        // 5. 提取并透传灰度流量染色路由标记 (X-Traffic-Tag)
        String trafficTag = resolveTrafficTag();
        addHeaderIfValid(template, FeignHeaderConstants.TRAFFIC_TAG, trafficTag);

        // 6. 提取并透传微服务零信任受信凭据 (Same-Token)
        if (sameTokenProvider != null) {
            String sameToken = sameTokenProvider.getSameToken();
            addHeaderIfValid(template, FeignHeaderConstants.SAME_TOKEN, sameToken);
        }

        // 7. 防御性清洗外部可能误写入的 "null" 脏字面量标头
        sanitizeHeaders(template);
    }

    private String resolveTraceId() {
        String traceId = MDC.get("traceId");
        if (!StringUtils.hasText(traceId)) {
            traceId = MDC.get(FeignHeaderConstants.TRACE_ID);
        }
        return traceId;
    }

    private String resolveTrafficTag() {
        String trafficTag = MDC.get(FeignHeaderConstants.TRAFFIC_TAG);
        if (!StringUtils.hasText(trafficTag)) {
            trafficTag = MDC.get("trafficTag");
        }
        if (!StringUtils.hasText(trafficTag)) {
            trafficTag = MDC.get("traffic-tag");
        }
        return trafficTag;
    }

    private void addHeaderIfValid(RequestTemplate template, String headerName, String headerValue) {
        if (headerValue == null) {
            return;
        }
        String trimmed = headerValue.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return;
        }
        // 如果调用方已在当前请求中显式指定该 Header，则不予覆盖
        if (template.headers().containsKey(headerName)) {
            return;
        }
        template.header(headerName, trimmed);
    }

    private void sanitizeHeaders(RequestTemplate template) {
        Map<String, Collection<String>> headers = template.headers();
        for (Map.Entry<String, Collection<String>> entry : headers.entrySet()) {
            Collection<String> values = entry.getValue();
            if (values != null && values.size() == 1) {
                String val = values.iterator().next();
                if (val != null && "null".equalsIgnoreCase(val.trim())) {
                    template.removeHeader(entry.getKey());
                }
            }
        }
    }
}
