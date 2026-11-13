package io.github.atengk.cloud.traffic.reactive;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.filter.TrafficInboundTagResolver;
import io.github.atengk.cloud.traffic.metrics.TrafficMetricsManager;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.rule.matcher.ReactiveTrafficEvaluationContext;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * WebFlux 响应式入站流量染色评估与双通道上下文绑定过滤器
 *
 * <p>核心治理语义（与 Servlet 栈物理隔离）：
 * 1. 复刻「Header ➔ Query ➔ 动态/静态分流规则」提取顺序与白名单纵深防护（STRIP / REJECT 4212）；
 * 2. 染色成功后通过 {@link ServerHttpRequest#mutate()} 向请求头注入标准标签，并通过 Reactor Context 写入 {@link TrafficContext}；
 * 3. 若发生 STRIP 剥离或基线保留字归一化，主动清洗伪造入站标头防止下游误读，严守零信任防穿透契约。</p>
 *
 * @author Ateng
 * @since 2026-11-13
 */
public class ReactiveTrafficContextWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ReactiveTrafficContextWebFilter.class);

    private final AtengTrafficProperties properties;
    private final TrafficInboundTagResolver inboundTagResolver;

    /**
     * 构造基础响应式入站流量过滤器
     *
     * @param properties 流量治理配置属性
     */
    public ReactiveTrafficContextWebFilter(AtengTrafficProperties properties) {
        this(properties, (TrafficRuleMatcher) null, (TrafficRuleProvider) null, (TrafficMetricsManager) null);
    }

    /**
     * 构造支持动态规则评估与度量上报的响应式过滤器
     *
     * @param properties     流量治理配置属性
     * @param ruleMatcher    规则匹配引擎
     * @param ruleProvider   规则提供者
     * @param metricsManager 监控指标管理器
     */
    public ReactiveTrafficContextWebFilter(AtengTrafficProperties properties,
                                           @Nullable TrafficRuleMatcher ruleMatcher,
                                           @Nullable TrafficRuleProvider ruleProvider,
                                           @Nullable TrafficMetricsManager metricsManager) {
        this(properties, () -> ruleMatcher, () -> ruleProvider, () -> metricsManager);
    }

    /**
     * 构造支持延迟获取依赖的响应式过滤器
     *
     * @param properties             流量治理配置属性
     * @param ruleMatcherSupplier    规则匹配引擎延迟提供者
     * @param ruleProviderSupplier   规则提供者延迟提供者
     * @param metricsManagerSupplier 监控指标管理器延迟提供者
     */
    public ReactiveTrafficContextWebFilter(AtengTrafficProperties properties,
                                           Supplier<TrafficRuleMatcher> ruleMatcherSupplier,
                                           Supplier<TrafficRuleProvider> ruleProviderSupplier,
                                           Supplier<TrafficMetricsManager> metricsManagerSupplier) {
        this.properties = properties != null ? properties : new AtengTrafficProperties();
        this.inboundTagResolver = new TrafficInboundTagResolver(
                this.properties, ruleMatcherSupplier, ruleProviderSupplier, metricsManagerSupplier);
    }

    @Override
    public int getOrder() {
        return properties.getFilterOrder();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        TrafficContext context;
        try {
            context = inboundTagResolver.resolveAndGuard(ReactiveTrafficEvaluationContext.from(exchange.getRequest()));
        } catch (BusinessException be) {
            return writeErrorResponse(exchange.getResponse(), be);
        }

        String headerName = inboundTagResolver.resolveHeaderName();
        if (context != null && context.hasTag()) {
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .headers(headers -> headers.set(headerName, context.getTag()))
                    .build();
            ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
            if (log.isDebugEnabled()) {
                log.debug("[TrafficReactiveFilter] 响应式入站请求已绑定流量染色标签: tag={}, uri={}",
                        context.getTag(), exchange.getRequest().getURI().getPath());
            }
            return chain.filter(mutatedExchange)
                    .contextWrite(ctx -> ctx.put(TrafficContext.class, context));
        }

        // 若外部传入的标头被 STRIP 剥离或归一化为基线，主动清除请求头防下游误染色
        if (exchange.getRequest().getHeaders().containsKey(headerName)) {
            ServerHttpRequest cleanedRequest = exchange.getRequest().mutate()
                    .headers(headers -> headers.remove(headerName))
                    .build();
            return chain.filter(exchange.mutate().request(cleanedRequest).build());
        }

        return chain.filter(exchange);
    }

    private Mono<Void> writeErrorResponse(ServerHttpResponse response, BusinessException be) {
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        byte[] payload = inboundTagResolver.toFailureJson(be).getBytes(StandardCharsets.UTF_8);
        Mono<DataBuffer> body = Mono.fromSupplier(() -> response.bufferFactory().wrap(payload))
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
        return response.writeWith(body);
    }
}
