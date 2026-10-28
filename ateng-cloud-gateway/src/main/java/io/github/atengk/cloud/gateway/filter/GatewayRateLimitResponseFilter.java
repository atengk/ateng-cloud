package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关分布式限流 HTTP 429 统一转译全局过滤器
 *
 * <p>微服务专属架构铁律落地：
 * <ul>
 *     <li><b>契约一致性</b>：拦截底层 RateLimiter 触发的 HTTP 429 状态，转译输出标准 Result JSON 契约；</li>
 *     <li><b>双重语义对齐</b>：保持物理状态码 HTTP 429 与 Result code 429 对齐；</li>
 *     <li><b>合规标头附加</b>：自动附加 {@code Retry-After} 限流状态响应头并透传 {@code X-Trace-Id}。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewayRateLimitResponseFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimitResponseFilter.class);

    private final ObjectMapper objectMapper;

    public GatewayRateLimitResponseFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public int getOrder() {
        return -80;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse originalResponse = exchange.getResponse();
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {

            @Override
            public Mono<Void> setComplete() {
                if (isTooManyRequests(getStatusCode())) {
                    return writeRateLimitPayload(this, exchange);
                }
                return super.setComplete();
            }

            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (isTooManyRequests(getStatusCode())) {
                    return writeRateLimitPayload(this, exchange);
                }
                return super.writeWith(body);
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    private boolean isTooManyRequests(HttpStatusCode statusCode) {
        return statusCode != null && statusCode.value() == HttpStatus.TOO_MANY_REQUESTS.value();
    }

    private Mono<Void> writeRateLimitPayload(ServerHttpResponseDecorator response, ServerWebExchange exchange) {
        String traceId = exchange.getAttribute(GatewayConstants.ATTR_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = response.getHeaders().getFirst(GatewayConstants.X_TRACE_ID);
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = exchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TRACE_ID);
        }

        HttpHeaders headers = response.getHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (traceId != null && !traceId.isBlank()) {
            headers.set(GatewayConstants.X_TRACE_ID, traceId);
        }
        if (!headers.containsKey(HttpHeaders.RETRY_AFTER)) {
            headers.set(HttpHeaders.RETRY_AFTER, "1");
        }

        Result<Void> result = Result.fail(HttpStatus.TOO_MANY_REQUESTS.value(), "当前访问过于频繁，请稍后重试");
        result.setTraceId(traceId);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (Exception ex) {
            log.error("[Gateway] 限流 429 响应序列化异常: {}", ex.getMessage());
            bytes = ("{\"code\":429,\"msg\":\"当前访问过于频繁，请稍后重试\",\"traceId\":\"" + traceId + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }

        DataBufferFactory bufferFactory = response.bufferFactory();
        DataBuffer buffer = bufferFactory.wrap(bytes);
        return response.getDelegate().writeWith(Mono.just(buffer));
    }
}
