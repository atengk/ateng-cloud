package io.github.atengk.cloud.nacos.mask;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Nacos 动态配置敏感属性脱敏屏障
 *
 * <p>微服务数据安全铁律：在配置变更审计日志、事件 toString 打印与外部监控导出边界，
 * 自动识别敏感特征键（password, secret, token, key, credential 等）并实施星号掩码脱敏（{@code ****** }），
 * 严格恪守“内存真明文”不变量，仅在日志与事件边界脱敏，杜绝明文凭据外泄。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class ConfigMasker {

    private static final List<String> SENSITIVE_KEYWORDS = Arrays.asList(
            "password",
            "passwd",
            "pwd",
            "secret",
            "token",
            "credential",
            "passphrase",
            "private-key",
            "privatekey",
            "privkey",
            "access-key",
            "accesskey",
            "secret-key",
            "secretkey",
            "apikey",
            "api-key"
    );

    private static final List<String> SENSITIVE_SUFFIXES_OR_EXACT = Arrays.asList(
            "key",
            "auth"
    );

    private ConfigMasker() {
    }

    /**
     * 判断配置键是否匹配敏感特征
     *
     * @param key 配置属性键名
     * @return 若命中敏感特征返回 true，否则返回 false
     */
    public static boolean isSensitive(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        String normalizedKey = key.trim().toLowerCase(Locale.ROOT);

        // 1. 检查包含型敏感词
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (normalizedKey.contains(keyword)) {
                return true;
            }
        }

        // 2. 检查后缀或独立段落型敏感词（如 .key, -key, _key, .auth 等）
        for (String suffix : SENSITIVE_SUFFIXES_OR_EXACT) {
            if (normalizedKey.equals(suffix)
                    || normalizedKey.endsWith("." + suffix)
                    || normalizedKey.endsWith("-" + suffix)
                    || normalizedKey.endsWith("_" + suffix)
                    || normalizedKey.contains("." + suffix + ".")
                    || normalizedKey.contains("-" + suffix + "-")
                    || normalizedKey.contains("_" + suffix + "_")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 对配置值执行防御性脱敏掩码
     *
     * @param key   配置属性键名
     * @param value 原始真实明文配置值
     * @return 若为敏感属性且非空则返回星号掩码（{@code ****** }），否则返回原始值
     */
    public static String mask(String key, String value) {
        if (value == null || !isSensitive(key)) {
            return value;
        }
        if (value.isEmpty()) {
            return "";
        }
        // 复用 ateng-boot-core 的通用脱敏原语
        return DesensitizeUtil.password(value);
    }
}
