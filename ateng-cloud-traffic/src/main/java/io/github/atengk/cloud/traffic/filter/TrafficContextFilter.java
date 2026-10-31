package io.github.atengk.cloud.traffic.filter;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.cloud.traffic.code.TrafficResultCode;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.metrics.TrafficMetricsManager;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.properties.TrafficWhitelistAction;
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
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Servlet 入站流量染色与全生命周期清理过滤器
 *
 * <p>核心治理语义：
 * 1. 优先从 HTTP 请求头 (默认 {@code X-Traffic-Tag}) 中提取流量染色标记；
 * 2. 次选从 URL Query 参数 (默认 {@code traffic_tag}) 中提取备选流量标记；
 * 3. 提取成功后自动注入 {@link TrafficContextHolder} 并同步至 SLF4J MDC；
 * 4. 严守资源清理不变量：在 {@code finally} 块中幂等执行清理，消除工作线程复用导致的脏读污染；
 * 5. 白名单安全防卫与度量埋点上报 (whitelist_stripped / whitelist_rejected)。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TrafficContextFilter.class);

    private final AtengTrafficProperties properties;
    private final Supplier<TrafficRuleMatcher> ruleMatcherSupplier;
    private final Supplier<TrafficRuleProvider> ruleProviderSupplier;
    private final Supplier<TrafficMetricsManager> metricsManagerSupplier;

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
        this.ruleMatcherSupplier = ruleMatcherSupplier != null ? ruleMatcherSupplier : () -> null;
        this.ruleProviderSupplier = ruleProviderSupplier != null ? ruleProviderSupplier : () -> null;
        this.metricsManagerSupplier = metricsManagerSupplier != null ? metricsManagerSupplier : () -> null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (properties.isEnabled()) {
                TrafficContext context;
                try {
                    context = resolveTrafficContext(request);
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
            Result<?> result = Result.failure(
                    be.getResultCode() != null ? be.getResultCode() : TrafficResultCode.UNAUTHORIZED_TRAFFIC_TAG,
                    be.getMessage());
            response.getWriter().write(JsonUtil.toJsonString(result));
            response.getWriter().flush();
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
        String headerTag = request.getHeader(headerName);
        if (StringUtils.hasText(headerTag)) {
            String sanitizedTag = validateAndSanitizeTag(headerTag.trim(), request);
            if (StringUtils.hasText(sanitizedTag)) {
                return TrafficContext.of(sanitizedTag);
            }
            // 若 Header 存在但被剥离清洗，直接按基线放行，避免二次从 Query 意外回填
            return null;
        }

        // 2. 备选读取 URL Query 参数
        String paramName = StringUtils.hasText(properties.getParamName())
                ? properties.getParamName() : TrafficConstants.PARAM_TRAFFIC_TAG;
        String paramTag = request.getParameter(paramName);
        if (StringUtils.hasText(paramTag)) {
            String sanitizedTag = validateAndSanitizeTag(paramTag.trim(), request);
            if (StringUtils.hasText(sanitizedTag)) {
                return TrafficContext.of(sanitizedTag);
            }
            return null;
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

    /**
     * 执行应用层外部流量染色标签白名单合法性校验与安全处置
     *
     * @param tag     提取到的入站流量标签
     * @param request HTTP 请求
     * @return 校验通过后的合法标签，若被剥离清洗或为基线标签则返回 null
     * @throws BusinessException 当白名单策略为 REJECT 且标签未授权时抛出 4212 业务异常
     */
    private String validateAndSanitizeTag(String tag, HttpServletRequest request) {
        if (!StringUtils.hasText(tag) || isBaselineTag(tag)) {
            return null;
        }

        Set<String> allowedTags = properties.getAllowedTags();
        if (allowedTags == null || allowedTags.isEmpty() || allowedTags.contains(tag)) {
            return tag;
        }

        TrafficWhitelistAction action = properties.getWhitelistAction();
        TrafficMetricsManager metricsManager = metricsManagerSupplier.get();
        if (action == TrafficWhitelistAction.REJECT) {
            if (metricsManager != null) {
                metricsManager.recordWhitelistRejected(tag);
            }
            log.warn("[TrafficFilter] 拒绝未授权的非法流量染色标签 (REJECT) [unauthorizedTag='{}', uri='{}', clientIp='{}', allowedTags={}]",
                    tag, request.getRequestURI(), resolveClientIp(request), allowedTags);
            throw new BusinessException(TrafficResultCode.UNAUTHORIZED_TRAFFIC_TAG,
                    String.format("未授权的非法流量染色标签 '%s'，已被安全防卫阻断 (unauthorized traffic-tag rejected)", tag));
        }

        // 默认 STRIP 剥离模式
        if (metricsManager != null) {
            metricsManager.recordWhitelistStripped(tag);
        }
        log.warn("[TrafficFilter] 检测到未授权流量染色标签，执行安全剥离降级至基线 (STRIP) [unauthorizedTag='{}', uri='{}', clientIp='{}', allowedTags={}]",
                    tag, request.getRequestURI(), resolveClientIp(request), allowedTags);
        return null;
    }

    private boolean isBaselineTag(String tag) {
        return TrafficConstants.DEFAULT_TAG.equalsIgnoreCase(tag)
                || TrafficConstants.TAG_BASELINE.equalsIgnoreCase(tag)
                || TrafficConstants.TAG_NONE.equalsIgnoreCase(tag);
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
            int idx = ip.indexOf(',');
            return idx != -1 ? ip.substring(0, idx).trim() : ip.trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
