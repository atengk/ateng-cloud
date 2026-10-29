package io.github.atengk.cloud.traffic.interceptor;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * RestTemplate 出站请求流量染色标头透传拦截器 (泳道穿透支持)
 *
 * <p>核心治理语义：
 * 当当前线程关联有效流量染色标签时，自动将标头 (默认 {@code X-Traffic-Tag}) 注入出站 HTTP 请求头中，
 * 确保即使在跨多级调用且中间节点降级的情况下，下游服务依然能够接收原始流量标签并重新回升至灰度泳道。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    private final AtengTrafficProperties properties;

    public TrafficClientHttpRequestInterceptor(AtengTrafficProperties properties) {
        this.properties = properties != null ? properties : new AtengTrafficProperties();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (properties.isEnabled() && properties.isLaneContinuity()) {
            String tag = TrafficContextHolder.getTag();
            if (StringUtils.hasText(tag)) {
                String headerName = StringUtils.hasText(properties.getHeaderName())
                        ? properties.getHeaderName() : TrafficConstants.HEADER_TRAFFIC_TAG;
                if (!request.getHeaders().containsKey(headerName)) {
                    request.getHeaders().set(headerName, tag.trim());
                }
            }
        }
        return execution.execute(request, body);
    }
}
