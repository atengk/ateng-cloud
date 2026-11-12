package io.github.atengk.cloud.feign.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.feign.constant.FeignHeaderConstants;
import io.github.atengk.cloud.feign.properties.AtengFeignContextProperties;
import io.github.atengk.cloud.feign.spi.SameTokenProvider;
import io.github.atengk.cloud.feign.support.FeignContextHeaderSupport;
import io.github.atengk.cloud.feign.support.FeignSeataPropagationSupport;
import io.github.atengk.cloud.feign.support.FeignTrafficPropagationSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Feign Servlet 入站全链路上下文对称还原与零信任防穿透过滤器
 * <p>
 * 对称承接消费端 {@code FeignContextPropagationInterceptor} 透传的四维上下文（traceId、userId、tenantId、ignoreTenant）、
 * 灰度泳道元数据（X-Traffic-Tag / X-Traffic-Lane / X-Traffic-Rule）及 Seata 全局事务 XID，
 * 并在请求结束的 {@code finally} 块中 100% 按入站前快照恢复或彻底清理，杜绝容器线程池复用污染。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-12
 */
public class FeignContextWebFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FeignContextWebFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final String TRAFFIC_FILTER_BEAN_ATTR = "trafficContextFilter" + OncePerRequestFilter.ALREADY_FILTERED_SUFFIX;
    private static final String TRAFFIC_FILTER_CLASS_ATTR =
            "io.github.atengk.cloud.traffic.filter.TrafficContextFilter" + OncePerRequestFilter.ALREADY_FILTERED_SUFFIX;

    private final AtengFeignContextProperties properties;
    private final SameTokenProvider sameTokenProvider;
    private final ObjectMapper objectMapper;

    /**
     * 构造 Feign 入站上下文还原与 Same-Token 防穿透过滤器
     *
     * @param properties        上下文传播配置属性
     * @param sameTokenProvider 零信任内部凭据校验提供器（可为空；开启校验但为空时执行 Fail-Closed 拒绝）
     * @param objectMapper      JSON 序列化器，用于输出标准 {@link Result} 401 响应体
     */
    public FeignContextWebFilter(AtengFeignContextProperties properties,
                                 SameTokenProvider sameTokenProvider,
                                 ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new AtengFeignContextProperties();
        this.sameTokenProvider = sameTokenProvider;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!verifySameTokenIfRequired(request, response)) {
            return;
        }

        if (!properties.isInboundEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        InboundSnapshot previousSnapshot = capturePreviousSnapshot();
        FeignSeataPropagationSupport.BindSnapshot seataBindSnapshot = null;
        try {
            seataBindSnapshot = applyInboundContext(request, previousSnapshot);
            filterChain.doFilter(request, response);
        } finally {
            restorePreviousSnapshot(previousSnapshot, seataBindSnapshot);
        }
    }

    private boolean verifySameTokenIfRequired(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!properties.isCheckSameToken() || isExcludedPath(request)) {
            return true;
        }

        String token = extractValidHeader(request, FeignHeaderConstants.SAME_TOKEN);
        if (token == null) {
            token = extractValidHeader(request, FeignHeaderConstants.SA_SAME_TOKEN);
        }

        if (token == null) {
            log.warn("[FEIGN-SECURITY] 零信任防穿透拦截: 请求 [{} {}] 未携带合法 Same-Token 受信凭据",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorizedResponse(response, "微服务内部调用未携带合法 Same-Token 受信凭据，拒绝非法访问");
            return false;
        }

        if (sameTokenProvider == null) {
            log.warn("[FEIGN-SECURITY] 已开启入站 Same-Token 校验但未装配 SameTokenProvider，安全拒绝访问 [{} {}]",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorizedResponse(response, "未装配内部 Same-Token 校验提供器，拒绝非法访问");
            return false;
        }

        boolean valid;
        try {
            valid = sameTokenProvider.isValidSameToken(token);
        } catch (Exception e) {
            log.warn("[FEIGN-SECURITY] 入站 Same-Token 校验执行异常: {}", e.getMessage());
            valid = false;
        }

        if (!valid) {
            log.warn("[FEIGN-SECURITY] 零信任防穿透拦截: 请求 [{} {}] 携带的 Same-Token 凭据校验无效",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorizedResponse(response, "微服务内部调用 Same-Token 凭据校验无效或已过期，拒绝非法访问");
            return false;
        }
        return true;
    }

    private boolean isExcludedPath(HttpServletRequest request) {
        List<String> excludePaths = properties.getExcludePaths();
        if (excludePaths == null || excludePaths.isEmpty()) {
            return false;
        }
        if (hasUnsafePathInput(request)) {
            log.warn("[FEIGN-SECURITY] 检测到疑似路径穿越或畸形矩阵路径，拒绝匹配白名单豁免: URI=[{}]",
                    request.getRequestURI());
            return false;
        }
        String path = resolveNormalizedRequestPath(request);
        if (!StringUtils.hasText(path)) {
            return false;
        }
        for (String pattern : excludePaths) {
            if (StringUtils.hasText(pattern) && PATH_MATCHER.match(pattern.trim(), path)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUnsafePathInput(HttpServletRequest request) {
        return containsUnsafePathOrEncoding(request.getRequestURI())
                || containsUnsafePathOrEncoding(request.getServletPath())
                || containsUnsafePathOrEncoding(request.getPathInfo());
    }

    private boolean containsUnsafePathOrEncoding(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            return false;
        }
        if (containsUnsafeTokens(rawPath)) {
            return true;
        }
        String current = rawPath;
        for (int i = 0; i < 2; i++) {
            String decoded;
            try {
                decoded = UriUtils.decode(current, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return true;
            }
            if (containsUnsafeTokens(decoded)) {
                return true;
            }
            if (decoded.equals(current)) {
                break;
            }
            current = decoded;
        }
        return false;
    }

    private boolean containsUnsafeTokens(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return path.indexOf('\0') >= 0
                || lower.contains("%00")
                || path.indexOf('\\') >= 0
                || lower.contains("%5c")
                || path.indexOf(';') >= 0
                || lower.contains("%3b")
                || lower.contains("..")
                || lower.contains("%2e%2e")
                || lower.contains("%2e.")
                || lower.contains(".%2e");
    }

    private String resolveNormalizedRequestPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String candidate;
        if (StringUtils.hasText(servletPath)) {
            candidate = StringUtils.hasText(pathInfo) ? servletPath + pathInfo : servletPath;
        } else {
            String uri = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (StringUtils.hasText(contextPath) && uri != null && uri.startsWith(contextPath)) {
                candidate = uri.substring(contextPath.length());
            } else {
                candidate = uri;
            }
            if (!StringUtils.hasText(candidate)) {
                candidate = "/";
            }
        }
        String collapsed = candidate.replaceAll("/{2,}", "/");
        return StringUtils.cleanPath(collapsed);
    }

    private void writeUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            Result<Void> errorResult = Result.failure(CommonResultCode.UNAUTHORIZED, message);
            response.getWriter().write(objectMapper.writeValueAsString(errorResult));
            response.getWriter().flush();
        }
    }

    private InboundSnapshot capturePreviousSnapshot() {
        return new InboundSnapshot(
                MDC.get(FeignHeaderConstants.MDC_TRACE_ID),
                MDC.get(FeignHeaderConstants.TRACE_ID),
                UserContextHolder.getExplicit(),
                TenantContextHolder.capturePrevious(),
                FeignTrafficPropagationSupport.getSnapshot(),
                FeignTrafficPropagationSupport.captureInboundSnapshot()
        );
    }

    private FeignSeataPropagationSupport.BindSnapshot applyInboundContext(HttpServletRequest request,
                                                                         InboundSnapshot previous) {
        // 1. 还原分布式链路 Trace ID
        String traceId = extractValidHeader(request, FeignHeaderConstants.TRACE_ID);
        if (traceId != null) {
            MDC.put(FeignHeaderConstants.MDC_TRACE_ID, traceId);
            MDC.put(FeignHeaderConstants.TRACE_ID, traceId);
        }

        // 2. 还原当前登录用户 ID
        String userId = extractValidHeader(request, FeignHeaderConstants.USER_ID);
        if (userId != null) {
            UserContextHolder.setUserId(userId);
        }

        // 3. 还原多租户 ID 与忽略租户标记
        String tenantId = extractValidHeader(request, FeignHeaderConstants.TENANT_ID);
        if (tenantId != null) {
            TenantContextHolder.setTenantId(tenantId);
        }
        String ignoreTenant = extractValidHeader(request, FeignHeaderConstants.IGNORE_TENANT);
        if ("true".equalsIgnoreCase(ignoreTenant)) {
            TenantContextHolder.setIgnoreTenant(true);
        }

        // 4. 还原灰度流量染色标签与泳道元数据
        // 若前置 TrafficContextFilter 已执行，以其安全裁决（含白名单 STRIP / 动态规则 matchedRule / 开关禁用）为准
        applyTrafficInboundContext(request, previous.prevTrafficSnapshot());

        // 5. 还原并绑定 Seata 全局事务 XID
        String inboundXid = extractValidHeader(request, FeignHeaderConstants.TX_XID);
        return FeignSeataPropagationSupport.bind(inboundXid);
    }

    private void applyTrafficInboundContext(HttpServletRequest request,
                                            FeignTrafficPropagationSupport.TrafficSnapshot prevTrafficSnapshot) {
        String trafficTag = extractValidHeader(request, FeignHeaderConstants.TRAFFIC_TAG);
        String trafficLane = extractValidHeader(request, FeignHeaderConstants.TRAFFIC_LANE);
        String trafficRule = extractValidHeader(request, FeignHeaderConstants.TRAFFIC_RULE);

        boolean trafficFilterExecuted = isTrafficContextFilterExecuted(request);
        String prevTag = prevTrafficSnapshot != null ? prevTrafficSnapshot.tag() : null;
        String prevLane = prevTrafficSnapshot != null ? prevTrafficSnapshot.laneName() : null;
        String prevRule = prevTrafficSnapshot != null ? prevTrafficSnapshot.matchedRule() : null;

        if (trafficFilterExecuted) {
            // 若前置 TrafficContextFilter 已剥离非法标签或关闭流量治理（prevTag == null），严禁重新激活原始 Header
            if (prevTag != null) {
                String effectiveLane = trafficLane != null ? trafficLane : prevLane;
                String effectiveRule = trafficRule != null ? trafficRule : prevRule;
                if (trafficLane != null || trafficRule != null) {
                    FeignTrafficPropagationSupport.restore(prevTag, effectiveLane, effectiveRule);
                } else {
                    FeignTrafficPropagationSupport.syncMdc(prevTag, effectiveLane, effectiveRule);
                }
            }
        } else if (trafficTag != null) {
            // 入站请求显式携带新 X-Traffic-Tag 时完整以入站头为准，绝不混入入站前线程残留的旧泳道/规则
            FeignTrafficPropagationSupport.restore(trafficTag, trafficLane, trafficRule);
        } else if (trafficLane != null || trafficRule != null) {
            String effectiveLane = trafficLane != null ? trafficLane : prevLane;
            String effectiveRule = trafficRule != null ? trafficRule : prevRule;
            FeignTrafficPropagationSupport.restore(prevTag, effectiveLane, effectiveRule);
        }
    }

    private boolean isTrafficContextFilterExecuted(HttpServletRequest request) {
        return request.getAttribute(TRAFFIC_FILTER_BEAN_ATTR) != null
                || request.getAttribute(TRAFFIC_FILTER_CLASS_ATTR) != null;
    }

    private void restorePreviousSnapshot(InboundSnapshot snapshot,
                                         FeignSeataPropagationSupport.BindSnapshot seataBindSnapshot) {
        FeignSeataPropagationSupport.unbind(seataBindSnapshot);
        if (snapshot == null) {
            return;
        }
        FeignTrafficPropagationSupport.restoreInboundSnapshot(snapshot.prevTrafficInboundSnapshot());
        TenantContextHolder.restore(snapshot.prevTenantSnapshot());
        if (snapshot.prevUserId() != null) {
            UserContextHolder.setUserId(snapshot.prevUserId());
        } else {
            UserContextHolder.clearUser();
        }
        restoreMdcKey(FeignHeaderConstants.MDC_TRACE_ID, snapshot.prevMdcTraceId());
        restoreMdcKey(FeignHeaderConstants.TRACE_ID, snapshot.prevHeaderMdcTraceId());
    }

    private void restoreMdcKey(String key, String previousValue) {
        if (previousValue != null) {
            MDC.put(key, previousValue);
        } else {
            MDC.remove(key);
        }
    }

    private String extractValidHeader(HttpServletRequest request, String headerName) {
        return FeignContextHeaderSupport.normalize(request.getHeader(headerName));
    }

    private record InboundSnapshot(
            String prevMdcTraceId,
            String prevHeaderMdcTraceId,
            String prevUserId,
            TenantContextHolder.TenantSnapshot prevTenantSnapshot,
            FeignTrafficPropagationSupport.TrafficSnapshot prevTrafficSnapshot,
            FeignTrafficPropagationSupport.InboundTrafficSnapshot prevTrafficInboundSnapshot
    ) {
    }
}
