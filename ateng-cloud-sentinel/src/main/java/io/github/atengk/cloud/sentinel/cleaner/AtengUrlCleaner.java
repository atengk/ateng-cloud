package io.github.atengk.cloud.sentinel.cleaner;

import com.alibaba.csp.sentinel.adapter.web.common.UrlCleaner;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.regex.Pattern;

/**
 * Sentinel RESTful URL 路径归一化清洗器
 *
 * <p>微服务高并发生产防爆设计：
 * <ul>
 *     <li><b>Spring MVC 模板优先</b>：优先提取 HandlerMapping 匹配成功的标准路由模式（如 {@code /orders/{id}}）；</li>
 *     <li><b>正则通配安全保底</b>：对未匹配模板或直接传入的动态 URL，将 UUID 替换为 {@code /{uuid}}，数值 ID 替换为 {@code /{id}}；</li>
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

    @Override
    public String clean(String originUrl) {
        if (!StringUtils.hasText(originUrl)) {
            return originUrl;
        }

        // 1. 优先尝试从当前请求上下文中提取 Spring MVC 路由模板
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes instanceof ServletRequestAttributes servletRequestAttributes) {
            HttpServletRequest request = servletRequestAttributes.getRequest();
            Object bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (bestMatchingPattern instanceof String pattern && StringUtils.hasText(pattern)) {
                return pattern;
            }
        }

        // 2. 若传入的 originUrl 已包含模板占位符 (如已是 /users/{id})，直接返回
        if (originUrl.contains("{") && originUrl.contains("}")) {
            return originUrl;
        }

        // 3. 保底使用高效预编译正则清洗 UUID 与纯数字 ID
        String cleaned = UUID_PATTERN.matcher(originUrl).replaceAll("/{uuid}");
        cleaned = NUMERIC_ID_PATTERN.matcher(cleaned).replaceAll("/{id}");

        return cleaned;
    }
}
