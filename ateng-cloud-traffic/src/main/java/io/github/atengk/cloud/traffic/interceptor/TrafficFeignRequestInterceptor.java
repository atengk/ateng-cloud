package io.github.atengk.cloud.traffic.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Feign 出站请求流量染色标头透传拦截器 (微服务东西向跨跳泳道穿透保持)
 *
 * <p>核心治理语义：
 * 1. 当微服务仅单独引入 {@code ateng-cloud-traffic} 与 OpenFeign（未引入 {@code ateng-cloud-feign}）时，
 *    自动挂载此拦截器，在出站 HTTP 请求头中安全透明注入当前线程的流量染色标签 (默认 {@code X-Traffic-Tag})；
 * 2. 具备严格的空值与脏字面量清洗防线，杜绝注入 {@code null}、空白字符或 {@code "null"} 脏字符串；
 * 3. 尊重显式契约：若调用方已在当前请求中显式设置同名有效 Header，则不予覆盖；若已有 Header 包含 {@code "null"} 脏值则主动剔除；
 * 4. 挂载最高执行优先级 (HIGHEST_PRECEDENCE)，确保在底层其它业务拦截器执行前完成流量染色标头注入。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class TrafficFeignRequestInterceptor implements RequestInterceptor, Ordered {

    private final AtengTrafficProperties properties;

    public TrafficFeignRequestInterceptor() {
        this(new AtengTrafficProperties());
    }

    public TrafficFeignRequestInterceptor(AtengTrafficProperties properties) {
        this.properties = properties != null ? properties : new AtengTrafficProperties();
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
        if (!properties.isEnabled() || !properties.isLaneContinuity()) {
            return;
        }

        String headerName = StringUtils.hasText(properties.getHeaderName())
                ? properties.getHeaderName().trim()
                : TrafficConstants.HEADER_TRAFFIC_TAG;

        // 1. 防御性清洗外部可能误写入的 "null" 或空白脏字面量标头
        sanitizeExistingHeader(template, headerName);

        // 2. 若当前请求已包含显式指定的有效 Header，恪守显式契约不予覆盖
        if (template.headers().containsKey(headerName)) {
            return;
        }

        // 3. 提取当前线程上下文关联的流量标签并安全注入
        String tag = TrafficContextHolder.getTag();
        if (isValidTag(tag)) {
            template.header(headerName, tag.trim());
        }
    }

    private void sanitizeExistingHeader(RequestTemplate template, String headerName) {
        Map<String, Collection<String>> headers = template.headers();
        Collection<String> values = headers.get(headerName);
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> validValues = new ArrayList<>();
        boolean hasDirty = false;
        for (String val : values) {
            if (val != null && StringUtils.hasText(val) && !"null".equalsIgnoreCase(val.trim())) {
                validValues.add(val.trim());
            } else {
                hasDirty = true;
            }
        }
        if (hasDirty) {
            template.removeHeader(headerName);
            if (!validValues.isEmpty()) {
                template.header(headerName, validValues);
            }
        }
    }

    private boolean isValidTag(String tag) {
        if (!StringUtils.hasText(tag)) {
            return false;
        }
        String trimmed = tag.trim();
        return !trimmed.isEmpty() && !"null".equalsIgnoreCase(trimmed);
    }
}
