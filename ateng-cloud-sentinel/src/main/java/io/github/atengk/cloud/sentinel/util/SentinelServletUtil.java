package io.github.atengk.cloud.sentinel.util;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.RequestOriginParser;
import com.alibaba.csp.sentinel.slots.block.AbstractRule;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Sentinel Servlet 运行时请求与阻断解析辅助工具类
 *
 * @author Ateng
 * @since 2026-10-29
 */
public final class SentinelServletUtil {

    private static final Logger log = LoggerFactory.getLogger(SentinelServletUtil.class);

    public static final String HEADER_BLOCK_RULE = "X-Sentinel-Block-Rule";
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    private SentinelServletUtil() {
    }

    /**
     * 解析客户端真实 IP 地址
     * <p>依次尝试按 {@code X-Forwarded-For} (逗号多 IP 截取第一项)、{@code X-Real-IP} 与 {@link HttpServletRequest#getRemoteAddr()} 回溯解析</p>
     *
     * @param request HTTP 请求对象
     * @return 客户端真实 IP，无法解析时保底返回 unknown
     */
    public static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr.trim() : "unknown";
    }

    /**
     * 安全解析请求调用源，避免自定义 SPI 异常中断阻断响应写出
     */
    public static String resolveOriginSafely(RequestOriginParser originParser, HttpServletRequest request) {
        if (originParser == null || request == null) {
            return null;
        }
        try {
            String origin = originParser.parseOrigin(request);
            return StringUtils.hasText(origin) ? origin.trim() : null;
        } catch (Exception ex) {
            log.warn("Failed to resolve Sentinel request origin via RequestOriginParser: {}", ex.getMessage(), ex);
            return null;
        }
    }

    /**
     * 从 MDC 提取当前链路追踪标识 (空白字符串归一为 {@code null})
     */
    public static String resolveTraceId() {
        String traceId = MDC.get("traceId");
        return StringUtils.hasText(traceId) ? traceId.trim() : null;
    }

    /**
     * 安全提取 {@link BlockException} 关联的规则资源名称（规避 Sentinel {@code getRule()} 在 rule 为 null 时抛出 NPE）
     *
     * @param ex              Sentinel 阻断异常
     * @param defaultResource 规则或资源名为空时的缺省资源名
     * @return 非空资源名称或缺省值
     */
    public static String resolveBlockResourceName(BlockException ex, String defaultResource) {
        if (ex != null) {
            try {
                AbstractRule rule = ex.getRule();
                if (rule != null && StringUtils.hasText(rule.getResource())) {
                    return rule.getResource().trim();
                }
            } catch (NullPointerException npe) {
                log.debug("BlockException rule is null when resolving resource name, fallback to '{}'", defaultResource);
            }
        }
        return defaultResource;
    }

    /**
     * 根据阻断异常类型解析标准规则类型标识
     */
    public static String determineRuleType(BlockException e) {
        if (e instanceof FlowException) {
            return "FLOW";
        } else if (e instanceof DegradeException) {
            return "DEGRADE";
        } else if (e instanceof ParamFlowException) {
            return "PARAM_FLOW";
        } else if (e instanceof SystemBlockException) {
            return "SYSTEM";
        } else if (e instanceof AuthorityException) {
            return "AUTHORITY";
        }
        return "UNKNOWN";
    }

    /**
     * 根据阻断异常类型解析对应的强类型错误码
     */
    public static SentinelResultCode determineResultCode(BlockException e) {
        if (e instanceof FlowException) {
            return SentinelResultCode.FLOW_LIMIT;
        } else if (e instanceof DegradeException) {
            return SentinelResultCode.DEGRADE;
        } else if (e instanceof ParamFlowException) {
            return SentinelResultCode.PARAM_FLOW_LIMIT;
        } else if (e instanceof SystemBlockException) {
            return SentinelResultCode.SYSTEM_BLOCK;
        } else if (e instanceof AuthorityException) {
            return SentinelResultCode.AUTHORITY_BLOCK;
        }
        return SentinelResultCode.FLOW_LIMIT;
    }

    /**
     * 根据阻断异常类型解析对应的物理 HTTP 状态码
     */
    public static HttpStatus determineHttpStatus(BlockException e) {
        if (e instanceof DegradeException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.TOO_MANY_REQUESTS;
    }

    /**
     * 根据 Sentinel 强类型错误码解析标准规则类型标识
     */
    public static String determineRuleType(SentinelResultCode resultCode) {
        if (resultCode == null) {
            return "UNKNOWN";
        }
        return switch (resultCode) {
            case FLOW_LIMIT -> "FLOW";
            case DEGRADE -> "DEGRADE";
            case PARAM_FLOW_LIMIT -> "PARAM_FLOW";
            case SYSTEM_BLOCK -> "SYSTEM";
            case AUTHORITY_BLOCK -> "AUTHORITY";
        };
    }

    /**
     * 根据 Sentinel 强类型错误码解析对应的物理 HTTP 状态码
     */
    public static HttpStatus determineHttpStatus(SentinelResultCode resultCode) {
        if (resultCode == SentinelResultCode.DEGRADE) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.TOO_MANY_REQUESTS;
    }

    /**
     * 从 {@link BusinessException} 中解析关联的 {@link SentinelResultCode}
     *
     * @param ex 业务异常实例
     * @return 匹配的 {@link SentinelResultCode}，普通业务异常返回 {@code null}
     */
    public static SentinelResultCode resolveSentinelResultCode(BusinessException ex) {
        if (ex == null) {
            return null;
        }
        if (ex.getResultCode() instanceof SentinelResultCode sentinelCode) {
            return sentinelCode;
        }
        if (ex.getResultCode() == null && ex.getCode() != null) {
            for (SentinelResultCode candidate : SentinelResultCode.values()) {
                if (candidate.getCode().equals(ex.getCode())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * 递归从异常链与反射/动态代理包装异常中提取底层 {@link BlockException}（带环检测防死循环）
     */
    public static BlockException extractBlockException(Throwable throwable) {
        return (BlockException) findInCausalChain(throwable, BlockException.class::isInstance);
    }

    /**
     * 在异常因果链及反射/代理包装异常（{@link UndeclaredThrowableException}、{@link InvocationTargetException}）
     * 中按谓词查找首个匹配异常（带环检测防死循环）
     */
    public static Throwable findInCausalChain(Throwable throwable, Predicate<Throwable> predicate) {
        if (throwable == null || predicate == null) {
            return null;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            if (predicate.test(current)) {
                return current;
            }
            Throwable next = current.getCause();
            if (next == null && current instanceof UndeclaredThrowableException ute) {
                next = ute.getUndeclaredThrowable();
            } else if (next == null && current instanceof InvocationTargetException ite) {
                next = ite.getTargetException();
            }
            current = next;
        }
        return null;
    }

    /**
     * 构造标准阻断响应 HTTP 响应头（{@code X-Sentinel-Block-Rule} 与可选 {@code Retry-After}）
     *
     * @param ruleType   规则类型标识（如 FLOW、DEGRADE）
     * @param retryAfter 退避等待时长（小于等于 0 时不写入 {@code Retry-After}）
     * @return 标准 {@link HttpHeaders}
     */
    public static HttpHeaders createBlockHeaders(String ruleType, Duration retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_BLOCK_RULE, StringUtils.hasText(ruleType) ? ruleType : "UNKNOWN");
        if (retryAfter != null && !retryAfter.isNegative() && !retryAfter.isZero()) {
            long retryAfterSeconds = retryAfter.toSeconds();
            if (retryAfterSeconds > 0) {
                headers.set(HEADER_RETRY_AFTER, String.valueOf(retryAfterSeconds));
            }
        }
        return headers;
    }

    /**
     * 将阻断或受管降级响应（HTTP 状态码、阻断响应头与 JSON {@link Result} 报文）安全写出至 {@link HttpServletResponse}
     *
     * @param response     Servlet 响应对象
     * @param objectMapper JSON 序列化器
     * @param statusCode   目标 HTTP 状态码
     * @param headers      预装配阻断响应头
     * @param body         统一响应体
     * @throws IOException 响应输出流写出失败时抛出
     */
    public static void writeBlockJsonResponse(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int statusCode,
            HttpHeaders headers,
            Result<Void> body) throws IOException {
        if (response == null || response.isCommitted()) {
            return;
        }
        response.setStatus(statusCode);
        if (headers != null) {
            headers.forEach((name, values) -> {
                if (values != null && !values.isEmpty()) {
                    response.setHeader(name, values.get(0));
                }
            });
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ObjectMapper safeMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        response.getWriter().write(safeMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    /**
     * 根据 {@link SentinelResultCode} 构造等价阻断异常供计数器按规则维度归类统计
     */
    public static BlockException createBlockExceptionForResultCode(
            SentinelResultCode resultCode,
            String resource,
            String message) {
        String safeResource = StringUtils.hasText(resource) ? resource : "unknown";
        String safeMessage = StringUtils.hasText(message) ? message : safeResource;
        if (resultCode == null) {
            return new DegradeException(safeResource, safeMessage);
        }
        return switch (resultCode) {
            case FLOW_LIMIT -> new FlowException(safeResource, safeMessage);
            case DEGRADE -> new DegradeException(safeResource, safeMessage);
            case PARAM_FLOW_LIMIT -> new ParamFlowException(safeResource, safeMessage);
            case SYSTEM_BLOCK -> new SystemBlockException(safeResource, safeMessage);
            case AUTHORITY_BLOCK -> new AuthorityException(safeResource, safeMessage);
        };
    }
}
