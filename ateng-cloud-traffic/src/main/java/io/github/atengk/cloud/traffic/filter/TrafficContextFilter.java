package io.github.atengk.cloud.traffic.filter;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.metrics.TrafficMetricsManager;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.rule.matcher.ServletTrafficEvaluationContext;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Servlet 入站流量染色与全生命周期清理过滤器
 *
 * <p>核心治理语义：
 * 1. 优先级顺序提取当前请求的流量标识：Header {@code X-Traffic-Tag} -> Query 参数 {@code traffic_tag} -> 动态/静态分流规则；
 * 2. 若白名单策略触发 {@code REJECT}，立即写出 4212 JSON 响应阻断；若成功解析则绑定 {@link TrafficContextHolder} 与 MDC；
 * 3. 在 {@code finally} 块中确定性幂等清理上下文，彻底杜绝线程池复用导致跨请求脏染色。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TrafficContextFilter.class);

    private final AtengTrafficProperties properties;
    private final TrafficInboundTagResolver inboundTagResolver;

    public TrafficContextFilter(AtengTrafficProperties properties) {
        this(properties, (TrafficRuleMatcher) null, (TrafficRuleProvider) null, (TrafficMetricsManager) null);
    }

    public TrafficContextFilter(AtengTrafficProperties properties,
                                TrafficRuleMatcher ruleMatcher,
                                TrafficRuleProvider ruleProvider) {
        this(properties, () -> ruleMatcher, () -> ruleProvider, () -> null);
    }

    public TrafficContextFilter(AtengTrafficProperties properties,
                                TrafficRuleMatcher ruleMatcher,
                                TrafficRuleProvider ruleProvider,
                                @Nullable TrafficMetricsManager metricsManager) {
        this(properties, () -> ruleMatcher, () -> ruleProvider, () -> metricsManager);
    }

    public TrafficContextFilter(AtengTrafficProperties properties,
                                Supplier<TrafficRuleMatcher> ruleMatcherSupplier,
                                Supplier<TrafficRuleProvider> ruleProviderSupplier) {
        this(properties, ruleMatcherSupplier, ruleProviderSupplier, () -> null);
    }

    public TrafficContextFilter(AtengTrafficProperties properties,
                                Supplier<TrafficRuleMatcher> ruleMatcherSupplier,
                                Supplier<TrafficRuleProvider> ruleProviderSupplier,
                                Supplier<TrafficMetricsManager> metricsManagerSupplier) {
        this.properties = properties != null ? properties : new AtengTrafficProperties();
        this.inboundTagResolver = new TrafficInboundTagResolver(
                this.properties, ruleMatcherSupplier, ruleProviderSupplier, metricsManagerSupplier);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (properties.isEnabled()) {
                TrafficContext context;
                try {
                    context = inboundTagResolver.resolveAndGuard(ServletTrafficEvaluationContext.from(request));
                } catch (BusinessException be) {
                    writeErrorResponse(response, be);
                    return;
                }

                if (context != null && context.hasTag()) {
                    TrafficContextHolder.setContext(context);
                    if (log.isDebugEnabled()) {
                        log.debug("[TrafficFilter] 入站请求已绑定流量染色标签: tag={}, uri={}",
                                context.getTag(), request.getRequestURI());
                    }
                } else {
                    // 无标请求主动清理，消除工作线程复用残留
                    TrafficContextHolder.clear();
                }
            } else {
                // 功能禁用时主动清理，消除工作线程复用残留
                TrafficContextHolder.clear();
            }

            filterChain.doFilter(request, response);
        } finally {
            TrafficContextHolder.clear();
        }
    }

    private void writeErrorResponse(HttpServletResponse response, BusinessException be) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(inboundTagResolver.toFailureJson(be));
            response.getWriter().flush();
        }
    }
}
