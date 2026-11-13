package io.github.atengk.cloud.sentinel.handler;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.RequestOriginParser;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.endpoint.SentinelBlockCounter;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import io.github.atengk.cloud.sentinel.util.SentinelServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * Sentinel Spring MVC Web 接口统一阻断异常处理器
 *
 * <p>实现物理 HTTP 状态码与强类型 {@link Result} 业务契约的双重对齐：
 * <ul>
 *     <li>限流保护 (Flow / Param / System / Authority)：返回 HTTP 429 Too Many Requests</li>
 *     <li>降级熔断 (Degrade)：返回 HTTP 503 Service Unavailable</li>
 * </ul>
 * 统一将阻断详情包装为 {@link SentinelResultCode} 对应业务错误体，输出 {@code X-Sentinel-Block-Rule}
 * 及可选 {@code Retry-After} 响应头，杜绝裸文本扩散。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class AtengBlockExceptionHandler implements BlockExceptionHandler {

    public static final String HEADER_BLOCK_RULE = SentinelServletUtil.HEADER_BLOCK_RULE;
    public static final String HEADER_RETRY_AFTER = SentinelServletUtil.HEADER_RETRY_AFTER;

    private static final Logger log = LoggerFactory.getLogger(AtengBlockExceptionHandler.class);

    private final ObjectMapper objectMapper;
    private final AtengSentinelProperties properties;
    private final SentinelBlockCounter blockCounter;
    private final RequestOriginParser originParser;

    public AtengBlockExceptionHandler(ObjectMapper objectMapper, AtengSentinelProperties properties) {
        this(objectMapper, properties, null, null);
    }

    public AtengBlockExceptionHandler(ObjectMapper objectMapper, AtengSentinelProperties properties, SentinelBlockCounter blockCounter) {
        this(objectMapper, properties, blockCounter, null);
    }

    public AtengBlockExceptionHandler(
            ObjectMapper objectMapper,
            AtengSentinelProperties properties,
            SentinelBlockCounter blockCounter,
            RequestOriginParser originParser) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.properties = properties != null ? properties : new AtengSentinelProperties();
        this.blockCounter = blockCounter;
        this.originParser = originParser;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, String resourceName, BlockException e) throws Exception {
        SentinelResultCode resultCode = determineResultCode(e);
        HttpStatus httpStatus = determineHttpStatus(e);
        String ruleType = determineRuleType(e);
        String clientIp = SentinelServletUtil.resolveClientIp(request);
        String origin = resolveOriginSafely(originParser, request);
        String traceId = SentinelServletUtil.resolveTraceId();

        if (blockCounter != null) {
            blockCounter.recordBlock(e, resourceName, clientIp, traceId, origin);
        }

        String requestPath = request != null ? request.getRequestURI() : "unknown";
        if (response == null) {
            log.warn("HttpServletResponse is null, skip Sentinel block handler response write: resource='{}', rule={}, code={}, status={}, path='{}', clientIp='{}', origin='{}', traceId='{}'",
                    resourceName, ruleType, resultCode.name(), httpStatus.value(), requestPath, clientIp, origin, traceId);
            return;
        }

        if (response.isCommitted()) {
            log.warn("Response already committed, skip Sentinel block handler response write: resource='{}', rule={}, code={}, status={}, path='{}', clientIp='{}', origin='{}', traceId='{}'",
                    resourceName, ruleType, resultCode.name(), httpStatus.value(), requestPath, clientIp, origin, traceId);
            return;
        }

        int targetStatus = properties.getBlockHandler().isHttpStatusAlign()
                ? httpStatus.value()
                : HttpStatus.OK.value();
        var headers = SentinelServletUtil.createBlockHeaders(ruleType, properties.getBlockHandler().getRetryAfter());
        Result<Void> result = Result.fail(resultCode.getCode(), resultCode.getMsg());

        log.warn("Sentinel block triggered: resource='{}', rule={}, code={}, status={}, path='{}', clientIp='{}', origin='{}', traceId='{}'",
                resourceName, ruleType, resultCode.name(), targetStatus, requestPath, clientIp, origin, traceId);

        SentinelServletUtil.writeBlockJsonResponse(response, objectMapper, targetStatus, headers, result);
    }

    /**
     * 安全解析请求调用源，避免自定义 SPI 异常中断阻断响应写出
     */
    public static String resolveOriginSafely(RequestOriginParser originParser, HttpServletRequest request) {
        return SentinelServletUtil.resolveOriginSafely(originParser, request);
    }

    /**
     * 根据阻断异常类型解析标准规则类型标识
     */
    public static String determineRuleType(BlockException e) {
        return SentinelServletUtil.determineRuleType(e);
    }

    /**
     * 根据阻断异常类型解析对应的强类型错误码
     */
    public static SentinelResultCode determineResultCode(BlockException e) {
        return SentinelServletUtil.determineResultCode(e);
    }

    /**
     * 根据阻断异常类型解析对应的物理 HTTP 状态码
     */
    public static HttpStatus determineHttpStatus(BlockException e) {
        return SentinelServletUtil.determineHttpStatus(e);
    }
}
