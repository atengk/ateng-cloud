package io.github.atengk.cloud.feign.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.feign.constant.FeignHeaderConstants;
import io.github.atengk.cloud.feign.spi.SameTokenProvider;
import io.github.atengk.cloud.feign.support.FeignContextHeaderSupport;
import io.github.atengk.cloud.feign.support.FeignSeataPropagationSupport;
import io.github.atengk.cloud.feign.support.FeignTrafficPropagationSupport;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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
public class FeignContextPropagationInterceptor implements RequestInterceptor, Ordered {

    private final SameTokenProvider sameTokenProvider;

    public FeignContextPropagationInterceptor() {
        this(null);
    }

    public FeignContextPropagationInterceptor(SameTokenProvider sameTokenProvider) {
        this.sameTokenProvider = sameTokenProvider;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (template == null) {
            return;
        }

        // 1. 先行清洗外部可能误写入的 "null" 或空白脏字面量标头，再执行有效上下文补充注入
        sanitizeHeaders(template);

        // 2. 提取并透传分布式链路 Trace ID
        String traceId = resolveTraceId();
        addHeaderIfValid(template, FeignHeaderConstants.TRACE_ID, traceId);

        // 3. 提取并透传当前登录用户 ID
        String userId = UserContextHolder.getUserId();
        addHeaderIfValid(template, FeignHeaderConstants.USER_ID, userId);

        // 4. 提取并透传当前多租户 ID
        String tenantId = TenantContextHolder.getTenantId();
        addHeaderIfValid(template, FeignHeaderConstants.TENANT_ID, tenantId);

        // 5. 提取并透传忽略租户标记 (仅当为 true 时显式透传，杜绝传递 false 或空值)
        if (TenantContextHolder.isIgnoreTenant()) {
            addHeaderIfValid(template, FeignHeaderConstants.IGNORE_TENANT, Boolean.TRUE.toString());
        }

        // 6. 提取并透传灰度流量染色与泳道元数据三元组 (优先 TrafficContextHolder，平滑回退 MDC)
        FeignTrafficPropagationSupport.TrafficSnapshot trafficSnapshot = FeignTrafficPropagationSupport.getSnapshot();
        if (trafficSnapshot != null && trafficSnapshot.isValid()) {
            addHeaderIfValid(template, FeignHeaderConstants.TRAFFIC_TAG, trafficSnapshot.tag());
            addHeaderIfValid(template, FeignHeaderConstants.TRAFFIC_LANE, trafficSnapshot.laneName());
            addHeaderIfValid(template, FeignHeaderConstants.TRAFFIC_RULE, trafficSnapshot.matchedRule());
        }

        // 7. 提取并透传 Seata 全局事务 XID (优先 RootContext，平滑回退 MDC)
        String xid = FeignSeataPropagationSupport.getXID();
        addHeaderIfValid(template, FeignHeaderConstants.TX_XID, xid);

        // 8. 提取并透传微服务零信任受信凭据 (Same-Token)
        if (sameTokenProvider != null) {
            String sameToken = sameTokenProvider.getSameToken();
            addHeaderIfValid(template, FeignHeaderConstants.SAME_TOKEN, sameToken);
        }
    }

    private String resolveTraceId() {
        return FeignContextHeaderSupport.firstValidMdcValue(
                FeignHeaderConstants.MDC_TRACE_ID,
                FeignHeaderConstants.TRACE_ID
        );
    }

    private void addHeaderIfValid(RequestTemplate template, String headerName, String headerValue) {
        String cleanValue = FeignContextHeaderSupport.normalize(headerValue);
        if (cleanValue == null) {
            return;
        }
        // 如果调用方已在当前请求中显式指定有效同名标头（忽略大小写），则不予覆盖
        if (template.headers().containsKey(headerName)) {
            return;
        }
        template.header(headerName, cleanValue);
    }

    private void sanitizeHeaders(RequestTemplate template) {
        Map<String, Collection<String>> headers = template.headers();
        for (Map.Entry<String, Collection<String>> entry : headers.entrySet()) {
            Collection<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            List<String> validValues = new ArrayList<>(values.size());
            boolean hasDirty = false;
            for (String val : values) {
                if (FeignContextHeaderSupport.normalize(val) != null) {
                    validValues.add(val);
                } else {
                    hasDirty = true;
                }
            }
            if (hasDirty) {
                template.removeHeader(entry.getKey());
                if (!validValues.isEmpty()) {
                    template.header(entry.getKey(), validValues);
                }
            }
        }
    }
}
