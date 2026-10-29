package io.github.atengk.cloud.traffic.filter;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.rule.matcher.ServletTrafficEvaluationContext;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Servlet 入站流量染色与全生命周期清理过滤器
 *
 * <p>核心治理语义：
 * 1. 优先从 HTTP 请求头 (默认 {@code X-Traffic-Tag}) 中提取流量染色标记；
 * 2. 次选从 URL Query 参数 (默认 {@code traffic_tag}) 中提取备选流量标记；
 * 3. 提取成功后自动注入 {@link TrafficContextHolder} 并同步至 SLF4J MDC；
 * 4. 严守资源清理不变量：在 {@code finally} 块中幂等执行清理，消除工作线程复用导致的脏读污染。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TrafficContextFilter.class);

    private final AtengTrafficProperties properties;
    private final java.util.function.Supplier<TrafficRuleMatcher> ruleMatcherSupplier;
    private final java.util.function.Supplier<TrafficRuleProvider> ruleProviderSupplier;

    public TrafficContextFilter(AtengTrafficProperties properties) {
        this(properties, (TrafficRuleMatcher) null, (TrafficRuleProvider) null);
    }

    public TrafficContextFilter(AtengTrafficProperties properties,
                                TrafficRuleMatcher ruleMatcher,
                                TrafficRuleProvider ruleProvider) {
        this(properties, () -> ruleMatcher, () -> ruleProvider);
    }

    public TrafficContextFilter(AtengTrafficProperties properties,
                                java.util.function.Supplier<TrafficRuleMatcher> ruleMatcherSupplier,
                                java.util.function.Supplier<TrafficRuleProvider> ruleProviderSupplier) {
        this.properties = properties != null ? properties : new AtengTrafficProperties();
        this.ruleMatcherSupplier = ruleMatcherSupplier != null ? ruleMatcherSupplier : () -> null;
        this.ruleProviderSupplier = ruleProviderSupplier != null ? ruleProviderSupplier : () -> null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (properties.isEnabled()) {
                TrafficContext context = resolveTrafficContext(request);
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

    /**
     * 解析当前 HTTP 请求中的流量染色上下文
     *
     * @param request HTTP 请求
     * @return 流量上下文快照，未解析出有效标签时返回 null
     */
    private TrafficContext resolveTrafficContext(HttpServletRequest request) {
        // 1. 优先读取 Header
        String headerName = StringUtils.hasText(properties.getHeaderName())
                ? properties.getHeaderName() : TrafficConstants.HEADER_TRAFFIC_TAG;
        String tag = request.getHeader(headerName);
        if (StringUtils.hasText(tag)) {
            return TrafficContext.of(tag.trim());
        }

        // 2. 备选读取 URL Query 参数
        String paramName = StringUtils.hasText(properties.getParamName())
                ? properties.getParamName() : TrafficConstants.PARAM_TRAFFIC_TAG;
        tag = request.getParameter(paramName);
        if (StringUtils.hasText(tag)) {
            return TrafficContext.of(tag.trim());
        }

        // 3. 动态流量分流规则匹配判定 (由 Nacos 动态推送或静态规则提供)
        TrafficRuleMatcher ruleMatcher = ruleMatcherSupplier.get();
        TrafficRuleProvider ruleProvider = ruleProviderSupplier.get();
        if (ruleMatcher != null && ruleProvider != null) {
            List<TrafficRule> rules = ruleProvider.getRules();
            if (rules != null && !rules.isEmpty()) {
                ServletTrafficEvaluationContext context = ServletTrafficEvaluationContext.from(request);
                TrafficRule matchedRule = ruleMatcher.matchRule(rules, context);
                if (matchedRule != null && StringUtils.hasText(matchedRule.getTargetTag())) {
                    String matchedTag = matchedRule.getTargetTag().trim();
                    if (log.isDebugEnabled()) {
                        log.debug("[TrafficFilter] 入站请求通过动态流量规则成功染色: tag={}, ruleId={}, uri={}",
                                matchedTag, matchedRule.getId(), request.getRequestURI());
                    }
                    return TrafficContext.of(matchedTag, null, matchedRule.getId());
                }
            }
        }

        return null;
    }
}
