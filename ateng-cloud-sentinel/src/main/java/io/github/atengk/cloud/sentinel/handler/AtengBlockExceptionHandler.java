package io.github.atengk.cloud.sentinel.handler;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * Sentinel Spring MVC Web 接口统一阻断异常处理器
 *
 * <p>实现物理 HTTP 状态码与强类型 {@link Result} 业务契约的双重对齐：
 * <ul>
 *     <li>限流保护 (Flow / Param / System / Authority)：返回 HTTP 429 Too Many Requests</li>
 *     <li>降级熔断 (Degrade)：返回 HTTP 503 Service Unavailable</li>
 * </ul>
 * 统一将阻断详情包装为 {@link SentinelResultCode} 对应业务错误体，杜绝裸文本扩散。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class AtengBlockExceptionHandler implements BlockExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AtengBlockExceptionHandler.class);

    private final ObjectMapper objectMapper;
    private final AtengSentinelProperties properties;

    public AtengBlockExceptionHandler(ObjectMapper objectMapper, AtengSentinelProperties properties) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.properties = properties != null ? properties : new AtengSentinelProperties();
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, String resourceName, BlockException e) throws Exception {
        SentinelResultCode resultCode = determineResultCode(e);
        HttpStatus httpStatus = determineHttpStatus(e);

        if (properties.getBlockHandler().isHttpStatusAlign()) {
            response.setStatus(httpStatus.value());
        } else {
            response.setStatus(HttpStatus.OK.value());
        }

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Result<Void> result = Result.fail(resultCode.getCode(), resultCode.getMsg());

        String clientIp = resolveClientIp(request);
        log.warn("Sentinel block triggered: resource='{}', rule={}, status={}, path='{}', clientIp='{}'",
                resourceName, resultCode.name(), response.getStatus(), request.getRequestURI(), clientIp);

        response.getWriter().write(objectMapper.writeValueAsString(result));
        response.getWriter().flush();
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
     * 辅助解析客户端真实 IP (防范多层代理)
     */
    private String resolveClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
            int index = ip.indexOf(',');
            return index != -1 ? ip.substring(0, index).trim() : ip.trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }
}
