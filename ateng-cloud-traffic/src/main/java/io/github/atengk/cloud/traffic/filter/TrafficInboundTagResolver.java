package io.github.atengk.cloud.traffic.filter;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.cloud.traffic.code.TrafficResultCode;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.metrics.TrafficMetricsManager;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.properties.TrafficWhitelistAction;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficEvaluationContext;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 传输栈无关的入站流量标签解析与白名单纵深校验器
 * <p>
 * 封装「Header ➔ Query ➔ 动态分流规则」提取顺序与 {@code allowed-tags} 校验 / 基线归一化语义，
 * 供 Servlet 过滤器与 WebFlux 响应式过滤器统一复用，严守零跨栈依赖与零重复逻辑。
 *
 * @author Ateng
 * @since 2026-11-13
 */
public final class TrafficInboundTagResolver {

    private static final Logger log = LoggerFactory.getLogger(TrafficInboundTagResolver.class);

    private final AtengTrafficProperties properties;
    private final Supplier<TrafficRuleMatcher> ruleMatcherSupplier;
    private final Supplier<TrafficRuleProvider> ruleProviderSupplier;
    private final Supplier<TrafficMetricsManager> metricsManagerSupplier;

    /**
     * 构造入站流量标签解析器
     *
     * @param properties             流量治理配置属性
     * @param ruleMatcherSupplier    规则匹配引擎提供者
     * @param ruleProviderSupplier   规则源提供者
     * @param metricsManagerSupplier 监控指标管理器提供者
     */
    public TrafficInboundTagResolver(AtengTrafficProperties properties,
                                     Supplier<TrafficRuleMatcher> ruleMatcherSupplier,
                                     Supplier<TrafficRuleProvider> ruleProviderSupplier,
                                     Supplier<TrafficMetricsManager> metricsManagerSupplier) {
        this.properties = properties != null ? properties : new AtengTrafficProperties();
        this.ruleMatcherSupplier = ruleMatcherSupplier != null ? ruleMatcherSupplier : () -> null;
        this.ruleProviderSupplier = ruleProviderSupplier != null ? ruleProviderSupplier : () -> null;
        this.metricsManagerSupplier = metricsManagerSupplier != null ? metricsManagerSupplier : () -> null;
    }

    /**
     * 获取当前生效的流量染色 HTTP 请求头名称
     *
     * @return 请求头名称
     */
    public String resolveHeaderName() {
        return properties.resolveHeaderName();
    }

    /**
     * 获取当前生效的流量染色 URL Query 参数名称
     *
     * @return 查询参数名称
     */
    public String resolveParamName() {
        return properties.resolveParamName();
    }

    /**
     * 执行「Header ➔ Query ➔ 动态分流规则」三级提取并应用白名单合法性防护与基线归一化
     *
     * @param evaluationContext 协议无关的评估上下文
     * @return 校验通过的流量上下文；未命中或被 STRIP 剥离/归一化为基线时返回 null
     * @throws BusinessException 当白名单策略为 REJECT 且标签未授权时抛出 4212 业务异常
     */
    public TrafficContext resolveAndGuard(TrafficEvaluationContext evaluationContext) {
        if (evaluationContext == null) {
            return null;
        }

        // 1. 优先读取 Header
        String headerTag = evaluationContext.getHeader(resolveHeaderName());
        if (StringUtils.hasText(headerTag)) {
            String sanitizedTag = validateAndSanitizeTag(headerTag.trim(), evaluationContext);
            if (StringUtils.hasText(sanitizedTag)) {
                return TrafficContext.of(sanitizedTag);
            }
            // 若 Header 存在但被剥离清洗，直接按基线放行，避免二次从 Query 意外回填
            return null;
        }

        // 2. 备选读取 URL Query 参数
        String paramTag = evaluationContext.getQueryParam(resolveParamName());
        if (StringUtils.hasText(paramTag)) {
            String sanitizedTag = validateAndSanitizeTag(paramTag.trim(), evaluationContext);
            if (StringUtils.hasText(sanitizedTag)) {
                return TrafficContext.of(sanitizedTag);
            }
            return null;
        }

        // 3. 动态流量分流规则匹配判定
        TrafficRuleMatcher ruleMatcher = ruleMatcherSupplier.get();
        TrafficRuleProvider ruleProvider = ruleProviderSupplier.get();
        if (ruleMatcher != null && ruleProvider != null) {
            List<TrafficRule> rules = ruleProvider.getRules();
            if (rules != null && !rules.isEmpty()) {
                TrafficRule matchedRule = ruleMatcher.matchRule(rules, evaluationContext);
                if (matchedRule != null && StringUtils.hasText(matchedRule.getTargetTag())) {
                    String matchedTag = validateAndSanitizeTag(matchedRule.getTargetTag().trim(), evaluationContext);
                    if (StringUtils.hasText(matchedTag)) {
                        if (log.isDebugEnabled()) {
                            log.debug("[TrafficFilter] 入站请求通过动态流量规则成功染色: tag={}, ruleId={}, uri={}",
                                    matchedTag, matchedRule.getId(), evaluationContext.getPath());
                        }
                        return TrafficContext.of(matchedTag, null, matchedRule.getId());
                    }
                    return null;
                }
            }
        }

        return null;
    }

    /**
     * 将白名单拒绝异常序列化为统一 {@link Result} JSON 响应文本
     *
     * @param exception 业务阻断异常
     * @return UTF-8 JSON 字符串
     */
    public String toFailureJson(BusinessException exception) {
        Result<?> result = Result.failure(
                exception.getResultCode() != null ? exception.getResultCode() : TrafficResultCode.UNAUTHORIZED_TRAFFIC_TAG,
                exception.getMessage());
        return JsonUtil.toJsonString(result);
    }

    private String validateAndSanitizeTag(String tag, TrafficEvaluationContext evaluationContext) {
        if (!StringUtils.hasText(tag) || isBaselineTag(tag)) {
            return null;
        }

        Set<String> allowedTags = properties.getAllowedTags();
        if (allowedTags == null || allowedTags.isEmpty() || allowedTags.contains(tag)) {
            return tag;
        }

        String path = evaluationContext.getPath();
        String clientIp = evaluationContext.getClientIp();
        if (!StringUtils.hasText(clientIp)) {
            clientIp = "unknown";
        }

        TrafficWhitelistAction action = properties.getWhitelistAction();
        TrafficMetricsManager metricsManager = metricsManagerSupplier.get();
        if (action == TrafficWhitelistAction.REJECT) {
            if (metricsManager != null) {
                metricsManager.recordWhitelistRejected(tag);
            }
            log.warn("[TrafficFilter] 拒绝未授权的非法流量染色标签 (REJECT) [unauthorizedTag='{}', uri='{}', clientIp='{}', allowedTags={}]",
                    tag, path, clientIp, allowedTags);
            throw new BusinessException(TrafficResultCode.UNAUTHORIZED_TRAFFIC_TAG,
                    String.format("未授权的非法流量染色标签 '%s'，已被安全防卫阻断 (unauthorized traffic-tag rejected)", tag));
        }

        // 默认 STRIP 剥离模式
        if (metricsManager != null) {
            metricsManager.recordWhitelistStripped(tag);
        }
        log.warn("[TrafficFilter] 检测到未授权流量染色标签，执行安全剥离降级至基线 (STRIP) [unauthorizedTag='{}', uri='{}', clientIp='{}', allowedTags={}]",
                tag, path, clientIp, allowedTags);
        return null;
    }

    /**
     * 判断给定流量标签是否为内置基线保留字（{@code default}、{@code baseline}、{@code none}）
     *
     * @param tag 流量标签
     * @return 为基线保留字时返回 {@code true}
     */
    public static boolean isBaselineTag(String tag) {
        return tag != null && (TrafficConstants.DEFAULT_TAG.equalsIgnoreCase(tag)
                || TrafficConstants.TAG_BASELINE.equalsIgnoreCase(tag)
                || TrafficConstants.TAG_NONE.equalsIgnoreCase(tag));
    }
}
