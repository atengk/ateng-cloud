package io.github.atengk.cloud.traffic.reactive;

import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * WebClient 响应式出站流量染色标头跨线程透传过滤器
 *
 * <p>核心治理语义：
 * 在 {@code lane-continuity=true} 下通过 {@link Mono#deferContextual} 优先从 Reactor Context 提取 {@link TrafficContext}
 * （缺失时平滑兜底探测 {@link TrafficContextHolder}），在不覆盖调用方显式有效同名标头的前提下，
 * 自动向出站 {@link ClientRequest} 注入 {@code X-Traffic-Tag}，保障跨调度线程切换时的响应式灰度路由一致性。</p>
 *
 * @author Ateng
 * @since 2026-11-13
 */
public class TrafficExchangeFilterFunction implements ExchangeFilterFunction {

    private final AtengTrafficProperties properties;

    /**
     * 构造响应式出站流量染色透传过滤器
     *
     * @param properties 流量治理配置属性
     */
    public TrafficExchangeFilterFunction(AtengTrafficProperties properties) {
        this.properties = properties != null ? properties : new AtengTrafficProperties();
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        if (!properties.isEnabled() || !properties.isLaneContinuity()) {
            return next.exchange(request);
        }

        String headerName = properties.resolveHeaderName();
        if (StringUtils.hasText(request.headers().getFirst(headerName))) {
            return next.exchange(request);
        }

        return Mono.deferContextual(ctxView -> {
            String tag = resolveTag(ctxView);
            if (!StringUtils.hasText(tag)) {
                return next.exchange(request);
            }
            ClientRequest mutatedRequest = ClientRequest.from(request)
                    .header(headerName, tag.trim())
                    .build();
            return next.exchange(mutatedRequest);
        });
    }

    private String resolveTag(ContextView contextView) {
        if (contextView.hasKey(TrafficContext.class)) {
            TrafficContext reactiveContext = contextView.get(TrafficContext.class);
            if (reactiveContext != null && reactiveContext.hasTag()) {
                return reactiveContext.getTag();
            }
        }
        return TrafficContextHolder.getTag();
    }
}
