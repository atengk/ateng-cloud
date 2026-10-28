package io.github.atengk.cloud.feign.logger;

import feign.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

/**
 * 生产级安全脱敏 Feign 请求响应日志门面
 * <p>
 * 继承 Feign 原生 {@link Logger}，依托 SLF4J 门面输出通信日志，
 * 在打印 HTTP 请求头与响应头时，对 Same-Token、Authorization、Cookie 等敏感机密标头
 * 执行动态星号掩码脱敏，杜绝生产日志明文泄露安全凭据。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignLogger extends Logger {

    private final org.slf4j.Logger logger;

    /**
     * 默认机密脱敏标头集合 (全小写匹配)
     */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "same-token",
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "token",
            "satoken",
            "x-token",
            "x-auth-token"
    );

    public AtengFeignLogger() {
        this(LoggerFactory.getLogger(AtengFeignLogger.class));
    }

    public AtengFeignLogger(Class<?> clazz) {
        this(LoggerFactory.getLogger(clazz != null ? clazz : AtengFeignLogger.class));
    }

    public AtengFeignLogger(String name) {
        this(LoggerFactory.getLogger(name != null ? name : AtengFeignLogger.class.getName()));
    }

    public AtengFeignLogger(org.slf4j.Logger logger) {
        this.logger = logger != null ? logger : LoggerFactory.getLogger(AtengFeignLogger.class);
    }

    @Override
    protected void log(String configKey, String format, Object... args) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        Object[] processedArgs = sanitizeArgs(format, args);
        logger.debug(String.format(methodTag(configKey) + format, processedArgs));
    }

    /**
     * 针对键值对标头日志参数执行安全脱敏清洗
     *
     * @param format 格式化模版
     * @param args   日志参数
     * @return 脱敏后的日志参数
     */
    protected Object[] sanitizeArgs(String format, Object[] args) {
        if ("%s: %s".equals(format) && args != null && args.length >= 2 && args[0] != null) {
            String headerName = String.valueOf(args[0]);
            if (isSensitiveHeader(headerName)) {
                Object[] copy = args.clone();
                String rawValue = args[1] != null ? String.valueOf(args[1]) : "";
                copy[1] = maskHeader(headerName, rawValue);
                return copy;
            }
        }
        return args;
    }

    /**
     * 判断指定标头是否属于机密凭据标头
     *
     * @param headerName 标头名称
     * @return true 表示属于敏感机密标头
     */
    public boolean isSensitiveHeader(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return false;
        }
        return SENSITIVE_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }

    /**
     * 对敏感机密标头值执行星号掩码脱敏
     *
     * @param headerName 标头名称
     * @param rawValue   原始明文凭据
     * @return 脱敏掩码凭据
     */
    public String maskHeader(String headerName, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "******";
        }
        int len = rawValue.length();
        if (len <= 6) {
            return "******";
        }
        return rawValue.substring(0, 2) + "******" + rawValue.substring(len - 2);
    }
}
