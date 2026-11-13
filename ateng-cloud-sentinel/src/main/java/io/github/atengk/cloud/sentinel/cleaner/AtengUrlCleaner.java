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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Sentinel RESTful URL 路径归一化清洗器
 *
 * <p>微服务高并发生产防爆设计：
 * <ul>
 *     <li><b>尾部斜杠归一化</b>：对长度大于 1 的非根路径截除尾部斜杠，避免 {@code /orders/1001} 与 {@code /orders/1001/} 分裂为双节点；</li>
 *     <li><b>白名单排除模式优先</b>：匹配排除白名单（默认包含 /actuator/**、Swagger/OpenAPI 等探针）时直接返回空字符串跳过资源创建；</li>
 *     <li><b>Spring MVC 模板防串扰提取</b>：仅当当前请求路径匹配且模板非泛化通配符（{@code /**}、{@code /*}）时提取路由模式，并对模板二次校验白名单；</li>
 *     <li><b>有序正则安全保底</b>：依次执行预编译自定义正则（支持捕获组 {@code $1} 等并做构造期 Fail-Fast 校验）、36 位 UUID、24/32 位纯十六进制哈希段及纯数字 ID 替换；</li>
 *     <li><b>根除内存无界泄漏</b>：杜绝因动态主键导致 Sentinel 内部 DefaultNode/ClusterNode 监控树发生 OOM 爆炸。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class AtengUrlCleaner implements UrlCleaner {

    private static final Pattern UUID_PATTERN = Pattern.compile("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?=/|$)");
    private static final Pattern HEX_ID_PATTERN = Pattern.compile("/(?:[0-9a-fA-F]{24}|[0-9a-fA-F]{32})(?=/|$)");
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("/\\d+(?=/|$)");

    private final PathMatcher pathMatcher = new AntPathMatcher();
    private final List<String> excludePatterns;
    private final List<CompiledCustomPattern> compiledCustomPatterns;
    private final String uuidReplacementQuoted;
    private final String numberReplacementQuoted;
    private final String hexReplacementQuoted;

    public AtengUrlCleaner() {
        this(new AtengSentinelProperties());
    }

    public AtengUrlCleaner(AtengSentinelProperties properties) {
        AtengSentinelProperties safeProperties = properties != null ? properties : new AtengSentinelProperties();
        AtengSentinelProperties.UrlCleanProperties urlClean = safeProperties.getUrlClean();
        this.excludePatterns = snapshotExcludePatterns(urlClean.getExcludePatterns());
        this.compiledCustomPatterns = compileCustomPatterns(urlClean.getCustomPatterns());
        this.uuidReplacementQuoted = Matcher.quoteReplacement(normalizeToken(urlClean.getUuidReplacement(), "{uuid}"));
        this.numberReplacementQuoted = Matcher.quoteReplacement(normalizeToken(urlClean.getNumberReplacement(), "{id}"));
        this.hexReplacementQuoted = Matcher.quoteReplacement(normalizeToken(urlClean.getHexReplacement(), "{id}"));
    }

    @Override
    public String clean(String originUrl) {
        if (!StringUtils.hasText(originUrl)) {
            return originUrl;
        }

        // 1. 归一化非根路径尾部斜杠
        String normalizedUrl = stripTrailingSlash(originUrl.trim());

        // 2. 优先判定排除白名单 (匹配成功时返回空字符串，跳过 Sentinel 资源创建)
        if (isExcluded(normalizedUrl)) {
            return "";
        }

        // 3. 尝试从当前匹配请求上下文中提取非通配的 Spring MVC 路由模板
        String matchedPattern = resolveMvcPattern(normalizedUrl);
        if (matchedPattern != null) {
            if (isExcluded(matchedPattern)) {
                return "";
            }
            return matchedPattern;
        }

        // 4. 按序执行预编译自定义正则与内置 UUID / 数字 ID / Hex 哈希正则清洗 (防范含 {x} 混入动态主键的旁路攻击)
        String cleaned = normalizedUrl;
        for (CompiledCustomPattern customPattern : compiledCustomPatterns) {
            cleaned = customPattern.pattern().matcher(cleaned).replaceAll(customPattern.replacement());
        }

        cleaned = UUID_PATTERN.matcher(cleaned).replaceAll(uuidReplacementQuoted);
        cleaned = HEX_ID_PATTERN.matcher(cleaned).replaceAll(hexReplacementQuoted);
        cleaned = NUMERIC_ID_PATTERN.matcher(cleaned).replaceAll(numberReplacementQuoted);

        return cleaned;
    }

    private boolean isExcluded(String url) {
        for (String pattern : excludePatterns) {
            if (pathMatcher.match(pattern, url)) {
                return true;
            }
        }
        return false;
    }

    private String resolveMvcPattern(String normalizedUrl) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (!(requestAttributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            return null;
        }
        HttpServletRequest request = servletRequestAttributes.getRequest();
        if (!matchesCurrentRequestPath(request, normalizedUrl)) {
            return null;
        }
        Object bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(bestMatchingPattern instanceof String rawPattern) || !StringUtils.hasText(rawPattern)) {
            return null;
        }
        String pattern = stripTrailingSlash(rawPattern.trim());
        if ("/**".equals(pattern) || "/*".equals(pattern)) {
            return null;
        }
        return pattern;
    }

    private boolean matchesCurrentRequestPath(HttpServletRequest request, String normalizedUrl) {
        if (request == null) {
            return false;
        }
        String requestUri = request.getRequestURI();
        if (StringUtils.hasText(requestUri) && normalizedUrl.equals(stripTrailingSlash(requestUri.trim()))) {
            return true;
        }
        String servletPath = request.getServletPath();
        return StringUtils.hasText(servletPath) && normalizedUrl.equals(stripTrailingSlash(servletPath.trim()));
    }

    private String stripTrailingSlash(String path) {
        String result = path;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private List<String> snapshotExcludePatterns(List<String> rawExcludePatterns) {
        if (rawExcludePatterns == null || rawExcludePatterns.isEmpty()) {
            return List.of();
        }
        List<String> snapshot = new ArrayList<>(rawExcludePatterns.size());
        for (String pattern : rawExcludePatterns) {
            if (StringUtils.hasText(pattern)) {
                snapshot.add(pattern.trim());
            }
        }
        return List.copyOf(snapshot);
    }

    private List<CompiledCustomPattern> compileCustomPatterns(List<AtengSentinelProperties.UrlCleanProperties.CustomPattern> rawPatterns) {
        if (rawPatterns == null || rawPatterns.isEmpty()) {
            return List.of();
        }
        List<CompiledCustomPattern> compiled = new ArrayList<>(rawPatterns.size());
        for (AtengSentinelProperties.UrlCleanProperties.CustomPattern item : rawPatterns) {
            if (item == null || !StringUtils.hasText(item.getPattern())) {
                continue;
            }
            try {
                Pattern pattern = Pattern.compile(item.getPattern().trim());
                String replacement = item.getReplacement() != null ? item.getReplacement() : "";
                validateCustomReplacement(pattern, replacement);
                compiled.add(new CompiledCustomPattern(pattern, replacement));
            } catch (PatternSyntaxException ex) {
                throw new IllegalArgumentException("Invalid custom url-clean regex pattern: " + item.getPattern(), ex);
            }
        }
        return List.copyOf(compiled);
    }

    private void validateCustomReplacement(Pattern pattern, String replacement) {
        int groupCount = pattern.matcher("").groupCount();
        Set<String> namedGroups = extractNamedGroups(pattern.pattern());
        for (int i = 0; i < replacement.length(); i++) {
            char ch = replacement.charAt(i);
            if (ch == '\\') {
                if (i + 1 >= replacement.length()) {
                    throw new IllegalArgumentException("Trailing escape character in custom url-clean replacement: " + replacement);
                }
                i++;
            } else if (ch == '$') {
                if (i + 1 >= replacement.length()) {
                    throw new IllegalArgumentException("Trailing group reference '$' in custom url-clean replacement: " + replacement);
                }
                char next = replacement.charAt(i + 1);
                if (next >= '0' && next <= '9') {
                    int groupIndex = next - '0';
                    if (groupIndex > groupCount) {
                        throw new IllegalArgumentException("No capturing group " + groupIndex + " for custom url-clean replacement: " + replacement);
                    }
                } else if (next == '{') {
                    int endIdx = replacement.indexOf('}', i + 2);
                    if (endIdx < 0) {
                        throw new IllegalArgumentException("Unclosed named group reference in custom url-clean replacement: " + replacement);
                    }
                    String groupName = replacement.substring(i + 2, endIdx);
                    if (!namedGroups.contains(groupName)) {
                        throw new IllegalArgumentException("No named capturing group '" + groupName + "' for custom url-clean replacement: " + replacement);
                    }
                    i = endIdx;
                } else {
                    throw new IllegalArgumentException("Illegal group reference in custom url-clean replacement: " + replacement);
                }
            }
        }
    }

    /**
     * 基于 JDK 17 兼容语法从正则源码提取已声明的命名捕获组 {@code (?<name>...)}，避免依赖 JDK 20+ {@code Pattern.namedGroups()}
     */
    private static Set<String> extractNamedGroups(String regex) {
        Set<String> names = new HashSet<>();
        boolean inCharClass = false;
        for (int i = 0; i < regex.length(); i++) {
            char ch = regex.charAt(i);
            if (ch == '\\') {
                if (i + 1 < regex.length() && regex.charAt(i + 1) == 'Q') {
                    int endQuote = regex.indexOf("\\E", i + 2);
                    i = endQuote >= 0 ? endQuote + 1 : regex.length() - 1;
                } else {
                    i++;
                }
                continue;
            }
            if (inCharClass) {
                if (ch == ']') {
                    inCharClass = false;
                }
                continue;
            }
            if (ch == '[') {
                inCharClass = true;
                if (i + 1 < regex.length() && regex.charAt(i + 1) == '^') {
                    i++;
                }
                if (i + 1 < regex.length() && regex.charAt(i + 1) == ']') {
                    i++;
                }
                continue;
            }
            if (ch == '(' && i + 3 < regex.length() && regex.charAt(i + 1) == '?' && regex.charAt(i + 2) == '<') {
                char first = regex.charAt(i + 3);
                if (isAsciiAlpha(first)) {
                    int end = i + 4;
                    while (end < regex.length() && isAsciiAlphaNumeric(regex.charAt(end))) {
                        end++;
                    }
                    if (end < regex.length() && regex.charAt(end) == '>') {
                        names.add(regex.substring(i + 3, end));
                        i = end;
                    }
                }
            }
        }
        return names;
    }

    private static boolean isAsciiAlpha(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    private static boolean isAsciiAlphaNumeric(char ch) {
        return isAsciiAlpha(ch) || (ch >= '0' && ch <= '9');
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

    private record CompiledCustomPattern(Pattern pattern, String replacement) {
    }
}
