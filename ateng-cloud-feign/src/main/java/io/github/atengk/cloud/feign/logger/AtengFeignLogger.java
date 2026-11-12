package io.github.atengk.cloud.feign.logger;

import feign.Logger;
import feign.Response;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import io.github.atengk.cloud.feign.support.FeignTargetSupport;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 生产级安全脱敏 Feign 请求响应日志门面
 * <p>
 * 继承 Feign 原生 {@link Logger}，依托 SLF4J 门面输出通信日志：
 * 1. 在打印 HTTP 请求头与响应头时，对 Same-Token、Authorization、Cookie 等敏感机密标头
 * 执行动态星号掩码脱敏，杜绝生产日志明文泄露安全凭据；
 * 2. 内置 Slow Call Detection 慢调用主动监测机制，当单次响应耗时达到或超过配置阈值时，
 * 无视 DEBUG 日志级别限制直接输出 WARN 结构化慢调用告警。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignLogger extends Logger {

    private final org.slf4j.Logger logger;
    private final Class<?> clientType;
    private final AtengFeignProperties properties;

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
        this(LoggerFactory.getLogger(AtengFeignLogger.class), null, null);
    }

    public AtengFeignLogger(Class<?> clazz) {
        this(clazz, null);
    }

    public AtengFeignLogger(String name) {
        this(LoggerFactory.getLogger(name != null ? name : AtengFeignLogger.class.getName()), null, null);
    }

    public AtengFeignLogger(org.slf4j.Logger logger) {
        this(logger, null, null);
    }

    public AtengFeignLogger(Class<?> clazz, AtengFeignProperties properties) {
        this(LoggerFactory.getLogger(clazz != null ? clazz : AtengFeignLogger.class), clazz, properties);
    }

    public AtengFeignLogger(org.slf4j.Logger logger, AtengFeignProperties properties) {
        this(logger, null, properties);
    }

    public AtengFeignLogger(org.slf4j.Logger logger, Class<?> clientType, AtengFeignProperties properties) {
        this.logger = logger != null ? logger : LoggerFactory.getLogger(AtengFeignLogger.class);
        this.clientType = clientType;
        this.properties = properties;
    }

    @Override
    protected Response logAndRebufferResponse(String configKey, Level logLevel, Response response, long elapsedTime)
            throws IOException {
        detectAndWarnSlowCall(configKey, response, elapsedTime);
        if (!logger.isDebugEnabled() || logLevel == Level.NONE) {
            return response;
        }
        return super.logAndRebufferResponse(configKey, logLevel, response, elapsedTime);
    }

    private void detectAndWarnSlowCall(String configKey, Response response, long elapsedTime) {
        if (elapsedTime < 0) {
            return;
        }
        List<String> candidateNames = resolveCandidateNames(response);
        Duration effectiveThreshold = FeignTargetSupport.resolveSlowCallThreshold(properties, candidateNames);
        if (effectiveThreshold == null || effectiveThreshold.compareTo(Duration.ZERO) <= 0) {
            return;
        }
        if (Duration.ofMillis(elapsedTime).compareTo(effectiveThreshold) < 0) {
            return;
        }
        String clientName = FeignTargetSupport.resolvePrimaryClientName(candidateNames);
        if (!StringUtils.hasText(clientName)) {
            clientName = "unknown";
        }
        String method = StringUtils.hasText(configKey) ? configKey : "unknown";
        Object statusDisplay = response != null ? response.status() : "unknown";
        logger.warn("Feign 慢调用告警: client={}, method={}, status={}, elapsed={}ms, threshold={}ms",
                clientName, method, statusDisplay, elapsedTime, effectiveThreshold.toMillis());
    }

    private List<String> resolveCandidateNames(Response response) {
        List<String> responseNames = FeignTargetSupport.resolveCandidateNames(response);
        if (clientType == null) {
            return responseNames;
        }
        List<String> typeNames = FeignTargetSupport.resolveCandidateNames(clientType, null, null);
        if (responseNames.isEmpty()) {
            return typeNames;
        }
        if (typeNames.isEmpty()) {
            return responseNames;
        }
        Set<String> merged = new LinkedHashSet<>(responseNames);
        merged.addAll(typeNames);
        return List.copyOf(merged);
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
