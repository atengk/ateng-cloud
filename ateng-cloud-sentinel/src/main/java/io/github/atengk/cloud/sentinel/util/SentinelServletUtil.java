package io.github.atengk.cloud.sentinel.util;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.RequestOriginParser;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * Sentinel Servlet 运行时请求与阻断解析辅助工具类
 *
 * @author Ateng
 * @since 2026-10-29
 */
public final class SentinelServletUtil {

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
        } catch (Exception ignored) {
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
}
