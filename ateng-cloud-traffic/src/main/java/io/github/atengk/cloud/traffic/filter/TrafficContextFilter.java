package io.github.atengk.cloud.traffic.filter;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

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

    public TrafficContextFilter(AtengTrafficProperties properties) {
        this.properties = properties != null ? properties : new AtengTrafficProperties();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String tag = resolveTrafficTag(request);
        if (StringUtils.hasText(tag)) {
            String trimmedTag = tag.trim();
            TrafficContextHolder.setTag(trimmedTag);
            if (log.isDebugEnabled()) {
                log.debug("[TrafficFilter] 入站请求已绑定流量染色标签: tag={}, uri={}", trimmedTag, request.getRequestURI());
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TrafficContextHolder.clear();
        }
    }

    /**
     * 解析当前 HTTP 请求中的流量染色标记
     *
     * @param request HTTP 请求
     * @return 流量标签，未解析出时返回 null
     */
    private String resolveTrafficTag(HttpServletRequest request) {
        // 1. 优先读取 Header
        String headerName = StringUtils.hasText(properties.getHeaderName())
                ? properties.getHeaderName() : TrafficConstants.HEADER_TRAFFIC_TAG;
        String tag = request.getHeader(headerName);
        if (StringUtils.hasText(tag)) {
            return tag;
        }

        // 2. 备选读取 URL Query 参数
        String paramName = StringUtils.hasText(properties.getParamName())
                ? properties.getParamName() : TrafficConstants.PARAM_TRAFFIC_TAG;
        tag = request.getParameter(paramName);
        if (StringUtils.hasText(tag)) {
            return tag;
        }

        return null;
    }
}
