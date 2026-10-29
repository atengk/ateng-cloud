package io.github.atengk.cloud.traffic.rule.matcher;

import io.github.atengk.cloud.traffic.rule.model.TrafficCondition;
import io.github.atengk.cloud.traffic.rule.model.TrafficMatchOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 纳秒级高性能多维谓词轻量匹配器
 * <p>
 * 支持 EXACT、IN、PREFIX、REGEX 纯原生匹配，坚决杜绝引入 SpEL 等重量级脚本引擎以保障极速判定与执行安全。
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficPredicateMatcher {

    private static final Logger log = LoggerFactory.getLogger(TrafficPredicateMatcher.class);

    /**
     * 预编译正则表达式缓存容量上限 (防御规则热更或动态正则导致的内存泄漏)
     */
    private static final int MAX_PATTERN_CACHE_SIZE = 1024;

    /**
     * 正则表达式编译缓存: key = pattern:caseSensitive
     */
    private final ConcurrentMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    /**
     * 执行单条件谓词匹配判断
     *
     * @param condition   匹配条件
     * @param actualValue 实际提取到的值 (可为 null)
     * @return true 表示符合条件，false 表示不符合
     */
    public boolean matches(TrafficCondition condition, String actualValue) {
        if (condition == null) {
            return true;
        }

        TrafficMatchOperator operator = condition.getOperator();
        if (operator == null) {
            operator = TrafficMatchOperator.EXACT;
        }

        return switch (operator) {
            case EXACT -> matchExact(condition, actualValue);
            case IN -> matchIn(condition, actualValue);
            case PREFIX -> matchPrefix(condition, actualValue);
            case REGEX -> matchRegex(condition, actualValue);
        };
    }

    /**
     * 精确匹配
     */
    private boolean matchExact(TrafficCondition condition, String actualValue) {
        return equalsString(actualValue, condition.getExpectedValue(), condition.isCaseSensitive());
    }

    /**
     * 集合包含匹配 (支持 expectedValues 列表以及逗号分隔的 expectedValue)
     */
    private boolean matchIn(TrafficCondition condition, String actualValue) {
        if (actualValue == null) {
            return false;
        }

        List<String> expectedValues = condition.getExpectedValues();
        boolean caseSensitive = condition.isCaseSensitive();

        // 1. 优先匹配 expectedValues 集合
        if (expectedValues != null && !expectedValues.isEmpty()) {
            for (String expected : expectedValues) {
                if (equalsString(actualValue, expected, caseSensitive)) {
                    return true;
                }
            }
        }

        // 2. 兜底匹配逗号分隔的 expectedValue
        String expectedValue = condition.getExpectedValue();
        if (expectedValue != null && !expectedValue.isBlank()) {
            String[] split = expectedValue.split(",");
            for (String item : split) {
                if (equalsString(actualValue, item.trim(), caseSensitive)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 辅助字符串判等，依据 caseSensitive 执行区分大小写或忽略大小写比较
     */
    private boolean equalsString(String actual, String expected, boolean caseSensitive) {
        if (actual == null) {
            return expected == null;
        }
        if (expected == null) {
            return false;
        }
        return caseSensitive ? actual.equals(expected) : actual.equalsIgnoreCase(expected);
    }

    /**
     * 前缀匹配
     */
    private boolean matchPrefix(TrafficCondition condition, String actualValue) {
        if (actualValue == null) {
            return false;
        }
        String prefix = condition.getExpectedValue();
        if (prefix == null) {
            return false;
        }
        if (condition.isCaseSensitive()) {
            return actualValue.startsWith(prefix);
        }
        return actualValue.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * 正则表达式匹配
     */
    private boolean matchRegex(TrafficCondition condition, String actualValue) {
        if (actualValue == null) {
            return false;
        }
        String regex = condition.getExpectedValue();
        if (regex == null || regex.isEmpty()) {
            return false;
        }

        Pattern pattern = getOrCreatePattern(regex, condition.isCaseSensitive());
        if (pattern == null) {
            return false;
        }
        return pattern.matcher(actualValue).matches();
    }

    /**
     * 从缓存获取或编译正则表达式
     */
    private Pattern getOrCreatePattern(String regex, boolean caseSensitive) {
        String cacheKey = regex + ":" + caseSensitive;
        Pattern pattern = patternCache.get(cacheKey);
        if (pattern != null) {
            return pattern;
        }

        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            Pattern compiled = Pattern.compile(regex, flags);
            if (patternCache.size() < MAX_PATTERN_CACHE_SIZE) {
                patternCache.putIfAbsent(cacheKey, compiled);
            }
            return compiled;
        } catch (PatternSyntaxException e) {
            log.warn("[TrafficPredicateMatcher] 正则表达式语法非法，忽略该规则判定 [regex='{}', caseSensitive={}]", regex, caseSensitive);
            return null;
        }
    }

    /**
     * 清理正则编译缓存
     */
    public void clearCache() {
        patternCache.clear();
    }
}
