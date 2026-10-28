package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 响应式无锁全链路追踪 Trace 过滤器
 *
 * <p>置于网关入站高优先级管道（{@link GatewayConstants#ORDER_TRACE}）：
 * <ul>
 *     <li>优先继承上游入站 {@code X-Trace-Id} 或 W3C {@code traceparent} 标识，若缺失则自动生成高熵 32 位 UUID；</li>
 *     <li>绑定至当前 Exchange 上下文属性（{@link GatewayConstants#ATTR_TRACE_ID}）与 Reactor {@link reactor.util.context.ContextView}；</li>
 *     <li>向下游微服务请求注入规范标头 {@code X-Trace-Id}，并在网关出站响应头中自动回写回传客户端；</li>
 *     <li>全程纯响应式无锁实现，彻底杜绝静态 ThreadLocal 在 Netty EventLoop 线程池引发的跨请求脏读与内存泄漏。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewayTraceFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayTraceFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 提取或生成唯一 traceId
        String traceId = resolveTraceId(exchange.getRequest());

        // 2. 存入 Exchange 属性供网关异常处理器、限流与审计日志统一读取
        exchange.getAttributes().put(GatewayConstants.ATTR_TRACE_ID, traceId);

        // 3. 注册响应提交钩子并预写响应头，确保无论是流式响应还是异常响应均能回写 traceId
        exchange.getResponse().beforeCommit(() -> {
            if (!exchange.getResponse().getHeaders().containsKey(GatewayConstants.X_TRACE_ID)) {
                exchange.getResponse().getHeaders().set(GatewayConstants.X_TRACE_ID, traceId);
            }
            return Mono.empty();
        });
        exchange.getResponse().getHeaders().set(GatewayConstants.X_TRACE_ID, traceId);

        // 4. 重写入站下游请求头
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(GatewayConstants.X_TRACE_ID, traceId)
                .build();

        // 5. 将 traceId 注入 Reactor 响应式 Context 并执行下游过滤器链
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .contextWrite(context -> context.put(GatewayConstants.ATTR_TRACE_ID, traceId)
                        .put(GatewayConstants.X_TRACE_ID, traceId));
    }

    /**
     * 解析或生成 TraceId
     */
    private String resolveTraceId(ServerHttpRequest request) {
        // 优先读取 X-Trace-Id
        String traceId = request.getHeaders().getFirst(GatewayConstants.X_TRACE_ID);
        if (StringUtils.hasText(traceId)) {
            return traceId.trim();
        }

        // 尝试从 W3C traceparent 格式解析: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
        String traceparent = request.getHeaders().getFirst("traceparent");
        if (StringUtils.hasText(traceparent)) {
            String[] parts = traceparent.trim().split("-");
            if (parts.length >= 2 && StringUtils.hasText(parts[1])) {
                return parts[1];
            }
        }

        // 兜底生成紧凑 32 位无中划线 UUID
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_TRACE;
    }
}
