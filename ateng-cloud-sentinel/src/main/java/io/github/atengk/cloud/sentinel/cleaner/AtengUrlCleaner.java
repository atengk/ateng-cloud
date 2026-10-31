package io.github.atengk.cloud.sentinel.cleaner;

import com.alibaba.csp.sentinel.adapter.web.common.UrlCleaner;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Sentinel RESTful URL 路径归一化清洗器
 *
 * <p>微服务高并发生产防爆设计：
 * <ul>
 *     <li><b>白名单排除模式优先</b>：匹配排除白名单（默认包含 /actuator/**、Swagger/OpenAPI 等探针）时直接返回空字符串跳过资源创建；</li>
 *     <li><b>Spring MVC 模板优先</b>：优先提取 HandlerMapping 匹配成功的标准路由模式（如 {@code /orders/{id}}）；</li>
 *     <li><b>正则通配安全保底</b>：对未匹配模板或直接传入的动态 URL，将 UUID 与数值 ID 替换为规范占位符；</li>
 *     <li><b>根除内存无界泄漏</b>：杜绝因动态主键导致 Sentinel 内部 DefaultNode/ClusterNode 监控树发生 OOM 爆炸。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class AtengUrlCleaner implements UrlCleaner {

    private static final Pattern UUID_PATTERN = Pattern.compile("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?=/|$)");
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("/\\d+(?=/|$)");

    private final AtengSentinelProperties properties;
    private final PathMatcher pathMatcher = new AntPathMatcher();

    public AtengUrlCleaner() {
        this(new AtengSentinelProperties());
    }

    public AtengUrlCleaner(AtengSentinelProperties properties) {
        this.properties = properties != null ? properties : new AtengSentinelProperties();
    }

    @Override
    public String clean(String originUrl) {
        if (!StringUtils.hasText(originUrl)) {
            return originUrl;
        }

        // 1. 优先判定排除白名单 (匹配成功时返回空字符串，跳过 Sentinel 资源创建)
        List<String> excludePatterns = properties.getUrlClean().getExcludePatterns();
        if (excludePatterns != null && !excludePatterns.isEmpty()) {
            for (String pattern : excludePatterns) {
                if (StringUtils.hasText(pattern) && pathMatcher.match(pattern.trim(), originUrl)) {
                    return "";
                }
            }
        }

        // 2. 尝试从当前请求上下文中提取 Spring MVC 路由模板
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes instanceof ServletRequestAttributes servletRequestAttributes) {
            HttpServletRequest request = servletRequestAttributes.getRequest();
            Object bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (bestMatchingPattern instanceof String pattern && StringUtils.hasText(pattern)) {
                return pattern;
            }
        }

        // 3. 若传入的 originUrl 已包含模板占位符 (如已是 /users/{id})，直接返回
        if (originUrl.contains("{") && originUrl.contains("}")) {
            return originUrl;
        }

        // 4. 保底使用高效预编译正则清洗 UUID 与纯数字 ID
        String uuidReplacement = normalizeToken(properties.getUrlClean().getUuidReplacement(), "{uuid}");
        String numberReplacement = normalizeToken(properties.getUrlClean().getNumberReplacement(), "{id}");

        String cleaned = UUID_PATTERN.matcher(originUrl).replaceAll(uuidReplacement);
        cleaned = NUMERIC_ID_PATTERN.matcher(cleaned).replaceAll(numberReplacement);

        return cleaned;
    }

    /**
     * 规范化占位符为 {@code /{token}} 格式
     */
    private String normalizeToken(String raw, String defaultToken) {
        if (!StringUtils.hasText(raw)) {
            raw = defaultToken;
        }
        String token = raw.trim();
        if (token.startsWith("/")) {
            token = token.substring(1);
        }
        if (!token.startsWith("{") || !token.endsWith("}")) {
            token = "{" + token + "}";
        }
        return "/" + token;
    }
}
