package io.github.atengk.cloud.gateway.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 网关访问审计 Query 参数脱敏、截断与旁路隔离工具类
 *
 * <p>核心治理语义：
 * 1. 内置 12 类高频敏感参数键匹配（不区分大小写），统一替换为 {@code ***}；
 * 2. 支持自定义敏感参数列表扩展，原样保留键名与非敏感值；
 * 3. 截断硬限制最大保留 256 字符，超出追加 {@code ...(truncated)}；
 * 4. 绝对旁路隔离防线：若遇到非法 {@code %} 转义或畸形控制字符，降级输出 {@code [malformed-query]}，坚决不中断业务请求流转。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public final class GatewayQuerySanitizer {

    /**
     * 清洗后 Query 字符串硬限制最大字符长度
     */
    public static final int MAX_QUERY_LENGTH = 256;

    /**
     * 超过最大字符长度时的截断后缀标记
     */
    public static final String TRUNCATED_SUFFIX = "...(truncated)";

    /**
     * 畸形 Query 字符串安全降级占位符
     */
    public static final String MALFORMED_QUERY = "[malformed-query]";

    /**
     * 空 Query 占位符
     */
    public static final String EMPTY_QUERY = "-";

    /**
     * 敏感参数脱敏掩码值
     */
    public static final String MASK_REPLACEMENT = "***";

    /**
     * 内置 12 类高频敏感参数键名（小写集合）
     */
    public static final Set<String> DEFAULT_MASK_KEYS = Set.of(
            "password",
            "pwd",
            "token",
            "secret",
            "key",
            "authorization",
            "auth",
            "access_token",
            "refresh_token",
            "mobile",
            "phone",
            "idcard"
    );

    private GatewayQuerySanitizer() {
    }

    /**
     * 使用内置敏感词集合脱敏 Query 查询参数
     *
     * @param rawQuery 原始 Query 字符串
     * @return 脱敏并防爆截断后的 Query 字符串
     */
    public static String sanitize(String rawQuery) {
        return sanitize(rawQuery, null);
    }

    /**
     * 使用内置敏感词及自定义敏感词集合脱敏 Query 查询参数
     *
     * @param rawQuery 原始 Query 字符串
     * @param customMaskKeys 自定义脱敏敏感词集合（不区分大小写）
     * @return 脱敏并防爆截断后的 Query 字符串
     */
    public static String sanitize(String rawQuery, Collection<String> customMaskKeys) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return EMPTY_QUERY;
        }

        try {
            // 1. 旁路安全防卫：快速检测并拦截非法控制字符 (ASCII < 0x20 且非 \t)
            for (int i = 0; i < rawQuery.length(); i++) {
                char c = rawQuery.charAt(i);
                if (c < 0x20 && c != '\t') {
                    return MALFORMED_QUERY;
                }
            }

            // 2. 旁路安全防卫：校验百分号编码合法性
            validatePercentEncoding(rawQuery);

            Set<String> maskKeys = getEffectiveMaskKeys(customMaskKeys);
            StringBuilder sb = new StringBuilder(rawQuery.length());

            String[] pairs = rawQuery.split("&", -1);
            for (int i = 0; i < pairs.length; i++) {
                if (i > 0) {
                    sb.append('&');
                }
                String pair = pairs[i];
                int eqIdx = pair.indexOf('=');
                if (eqIdx == -1) {
                    String rawKey = pair;
                    String decodedKey = decodeSafely(rawKey);
                    if (isMaskKey(decodedKey, maskKeys)) {
                        sb.append(rawKey).append('=').append(MASK_REPLACEMENT);
                    } else {
                        sb.append(rawKey);
                    }
                } else {
                    String rawKey = pair.substring(0, eqIdx);
                    String rawVal = pair.substring(eqIdx + 1);
                    String decodedKey = decodeSafely(rawKey);
                    if (isMaskKey(decodedKey, maskKeys)) {
                        sb.append(rawKey).append('=').append(MASK_REPLACEMENT);
                    } else {
                        sb.append(rawKey).append('=').append(rawVal);
                    }
                }
            }

            String sanitized = sb.toString();
            // 3. 防撑爆硬限制：超过 256 字符安全截断
            if (sanitized.length() > MAX_QUERY_LENGTH) {
                return sanitized.substring(0, MAX_QUERY_LENGTH) + TRUNCATED_SUFFIX;
            }
            return sanitized;
        } catch (Throwable t) {
            // 4. 绝对旁路隔离：遇到任何非法字符或解析异常，降级兜底，永不中断请求链路
            return MALFORMED_QUERY;
        }
    }

    /**
     * 校验字符串中是否包含非法 % 转义
     */
    private static void validatePercentEncoding(String str) {
        if (str.indexOf('%') == -1) {
            return;
        }
        // 调用标准解码器触发非法 hex 或不完整 % 校验
        URLDecoder.decode(str, StandardCharsets.UTF_8);
    }

    /**
     * 安全解码 Key，用于敏感词匹配
     */
    private static String decodeSafely(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) {
            return "";
        }
        if (rawKey.indexOf('%') != -1) {
            try {
                return URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return rawKey;
            }
        }
        return rawKey;
    }

    /**
     * 判断当前 Key 是否命中敏感词集合（大小写不敏感）
     */
    private static boolean isMaskKey(String key, Set<String> maskKeys) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return maskKeys.contains(key.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 聚合内置敏感词与自定义敏感词集合
     */
    private static Set<String> getEffectiveMaskKeys(Collection<String> customMaskKeys) {
        if (customMaskKeys == null || customMaskKeys.isEmpty()) {
            return DEFAULT_MASK_KEYS;
        }
        Set<String> set = new HashSet<>(DEFAULT_MASK_KEYS);
        for (String k : customMaskKeys) {
            if (k != null && !k.isBlank()) {
                set.add(k.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(set);
    }
}
