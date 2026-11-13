package io.github.atengk.cloud.sentinel.advice;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.RequestOriginParser;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.endpoint.SentinelBlockCounter;
import io.github.atengk.cloud.sentinel.fallback.SentinelBlockTracked;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import io.github.atengk.cloud.sentinel.util.SentinelServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

/**
 * Sentinel {@code @SentinelResource} 切面逃逸与受管降级异常全局兜底处理器
 * <p>
 * 同时覆盖三类场景并统一对齐 HTTP 429/503 物理状态码与 {@code X-Sentinel-Block-Rule} / {@code Retry-After} 响应头：
 * <ol>
 *   <li>标注 {@code @SentinelResource} 且未配置 {@code blockHandler} 时逃逸的 {@link BlockException}；</li>
 *   <li>动态代理或反射包装的 {@link UndeclaredThrowableException} / {@link InvocationTargetException}；</li>
 *   <li>携带 {@link SentinelResultCode} 的出站受管降级 {@link BusinessException}（支持防重计数守卫，普通业务异常无损放行）。</li>
 * </ol>
 * 通过实现 {@link HandlerExceptionResolver} 在 {@code ExceptionHandlerExceptionResolver} 之前精准分流，
 * 避免抢占普通 {@link BusinessException} 或其他未包装异常的全局异常处理权。
 *
 * @author Ateng
 * @since 2026-10-29
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class SentinelBlockExceptionAdvice implements HandlerExceptionResolver, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SentinelBlockExceptionAdvice.class);

    private final AtengSentinelProperties properties;
    private final SentinelBlockCounter blockCounter;
    private final RequestOriginParser originParser;
    private final ObjectMapper objectMapper;

    /**
     * 构建切面兜底处理器（仅依赖配置属性）
     *
     * @param properties Sentinel 配置属性
     */
    public SentinelBlockExceptionAdvice(AtengSentinelProperties properties) {
        this(properties, null, null, null);
    }

    /**
     * 构建切面兜底处理器（附加阻断计数器）
     *
     * @param properties   Sentinel 配置属性
     * @param blockCounter Sentinel 原子阻断计数器
     */
    public SentinelBlockExceptionAdvice(AtengSentinelProperties properties, SentinelBlockCounter blockCounter) {
        this(properties, blockCounter, null, null);
    }

    /**
     * 构建切面兜底处理器（附加阻断计数器与来源解析器）
     *
     * @param properties   Sentinel 配置属性
     * @param blockCounter Sentinel 原子阻断计数器
     * @param originParser 请求调用源解析器
     */
    public SentinelBlockExceptionAdvice(
            AtengSentinelProperties properties,
            SentinelBlockCounter blockCounter,
            RequestOriginParser originParser) {
        this(properties, blockCounter, originParser, null);
    }

    /**
     * 完整构造切面兜底处理器
     *
     * @param properties   Sentinel 配置属性
     * @param blockCounter Sentinel 原子阻断计数器（可为 {@code null}）
     * @param originParser 请求调用源解析器（可为 {@code null}）
     * @param objectMapper JSON 序列化器（为空时使用默认 {@link ObjectMapper}）
     */
    public SentinelBlockExceptionAdvice(
            AtengSentinelProperties properties,
            SentinelBlockCounter blockCounter,
            RequestOriginParser originParser,
            ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new AtengSentinelProperties();
        this.blockCounter = blockCounter;
        this.originParser = originParser;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * 返回切面处理器排序优先级，确保在 {@code ExceptionHandlerExceptionResolver} 之前执行拦截
     *
     * @return 优先级值 {@code Ordered.HIGHEST_PRECEDENCE + 100}
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        if (ex == null) {
            return null;
        }
        ResponseEntity<Result<Void>> entity = resolveSentinelResponse(ex, request);
        if (entity == null) {
            return null;
        }
        if (response != null) {
            if (response.isCommitted()) {
                log.warn("Response already committed, skip Sentinel advice response write: path='{}'",
                        request != null ? request.getRequestURI() : "unknown");
                return new ModelAndView();
            }
            try {
                SentinelServletUtil.writeBlockJsonResponse(
                        response, objectMapper, entity.getStatusCode().value(), entity.getHeaders(), entity.getBody());
            } catch (Exception writeEx) {
                log.warn("Failed to write Sentinel advice JSON response", writeEx);
            }
        }
        return new ModelAndView();
    }

    /**
     * 直接处理逃逸的 {@link BlockException}（兼顾局部切片测试与标准 `@RestControllerAdvice` 回调）
     *
     * @param ex      Sentinel 阻断异常
     * @param request 当前 HTTP 请求
     * @return 对齐 HTTP 429/503 与 {@link SentinelResultCode} 的响应实体
     */
    @ExceptionHandler(BlockException.class)
    public ResponseEntity<Result<Void>> handleBlockException(BlockException ex, HttpServletRequest request) {
        return buildBlockResponse(ex, request, false);
    }

    /**
     * 处理动态代理包装的 {@link UndeclaredThrowableException}
     *
     * @param ex      代理未受检包装异常
     * @param request 当前 HTTP 请求
     * @return 对齐 HTTP 429/503 与 {@link SentinelResultCode} 的响应实体
     * @throws Throwable 当内部不包含 Sentinel 阻断或受管降级异常时原样重新抛出
     */
    public ResponseEntity<Result<Void>> handleUndeclaredThrowableException(
            UndeclaredThrowableException ex,
            HttpServletRequest request) throws Throwable {
        ResponseEntity<Result<Void>> response = resolveWrappedResponse(ex, request);
        if (response != null) {
            return response;
        }
        throw ex;
    }

    /**
     * 处理反射调用包装的 {@link InvocationTargetException}
     *
     * @param ex      反射调用目标异常
     * @param request 当前 HTTP 请求
     * @return 对齐 HTTP 429/503 与 {@link SentinelResultCode} 的响应实体
     * @throws Throwable 当内部不包含 Sentinel 阻断或受管降级异常时原样重新抛出
     */
    public ResponseEntity<Result<Void>> handleInvocationTargetException(
            InvocationTargetException ex,
            HttpServletRequest request) throws Throwable {
        ResponseEntity<Result<Void>> response = resolveWrappedResponse(ex, request);
        if (response != null) {
            return response;
        }
        throw ex;
    }

    /**
     * 拦截携带 {@link SentinelResultCode} 的出站受管降级 {@link BusinessException}；
     * 若为普通业务异常（非 {@link SentinelResultCode}），原样重新抛出交由全局业务异常切面渲染。
     *
     * @param ex      业务异常实例
     * @param request 当前 HTTP 请求
     * @return 对齐 HTTP 429/503 与 {@link SentinelResultCode} 的响应实体
     */
    public ResponseEntity<Result<Void>> handleBusinessException(
            BusinessException ex,
            HttpServletRequest request) {
        SentinelResultCode resultCode = SentinelServletUtil.resolveSentinelResultCode(ex);
        if (resultCode == null) {
            throw ex;
        }
        boolean alreadyCounted = ex instanceof SentinelBlockTracked tracked && tracked.isSourceCounted();
        BlockException underlyingBlock = extractBlockException(ex);
        if (underlyingBlock != null) {
            SentinelResultCode effectiveCode = SentinelServletUtil.determineResultCode(underlyingBlock);
            String resource = resolveResourceName(underlyingBlock, request);
            return buildBlockResponse(underlyingBlock, effectiveCode, request, alreadyCounted, resource);
        }
        String resource = request != null ? request.getRequestURI() : "feign-fallback";
        BlockException syntheticBlock = SentinelServletUtil.createBlockExceptionForResultCode(
                resultCode, resource, ex.getMessage());
        return buildBlockResponse(syntheticBlock, resultCode, request, alreadyCounted, resource);
    }

    private ResponseEntity<Result<Void>> resolveSentinelResponse(Throwable ex, HttpServletRequest request) {
        if (ex instanceof BlockException blockException) {
            return buildBlockResponse(blockException, request, false);
        }
        if (ex instanceof UndeclaredThrowableException || ex instanceof InvocationTargetException) {
            return resolveWrappedResponse(ex, request);
        }
        if (ex instanceof BusinessException businessException
                && SentinelServletUtil.resolveSentinelResultCode(businessException) != null) {
            return handleBusinessException(businessException, request);
        }
        return null;
    }

    /**
     * 统一解包代理/反射包装异常：优先匹配携带 {@link SentinelResultCode} 与 {@link SentinelBlockTracked}
     * 语义的受管业务异常，确保内层因果 {@link BlockException} 不被穿透并破坏源头防重计数守卫。
     */
    private ResponseEntity<Result<Void>> resolveWrappedResponse(Throwable wrapperEx, HttpServletRequest request) {
        BusinessException sentinelBiz = extractSentinelBusinessException(wrapperEx);
        if (sentinelBiz != null) {
            return handleBusinessException(sentinelBiz, request);
        }
        BlockException blockException = extractBlockException(wrapperEx);
        if (blockException != null) {
            return buildBlockResponse(blockException, request, false);
        }
        return null;
    }

    private ResponseEntity<Result<Void>> buildBlockResponse(
            BlockException ex,
            HttpServletRequest request,
            boolean skipCounter) {
        SentinelResultCode resultCode = SentinelServletUtil.determineResultCode(ex);
        String resource = resolveResourceName(ex, request);
        return buildBlockResponse(ex, resultCode, request, skipCounter, resource);
    }

    private ResponseEntity<Result<Void>> buildBlockResponse(
            BlockException blockForCounter,
            SentinelResultCode resultCode,
            HttpServletRequest request,
            boolean skipCounter,
            String resource) {
        HttpStatus targetStatus = SentinelServletUtil.determineHttpStatus(resultCode);
        String ruleType = SentinelServletUtil.determineRuleType(resultCode);
        String clientIp = SentinelServletUtil.resolveClientIp(request);
        String origin = SentinelServletUtil.resolveOriginSafely(originParser, request);
        String traceId = SentinelServletUtil.resolveTraceId();

        if (!skipCounter && blockCounter != null) {
            blockCounter.recordBlock(blockForCounter, resource, clientIp, traceId, origin);
        }

        HttpHeaders headers = SentinelServletUtil.createBlockHeaders(ruleType, properties.getBlockAdvice().getRetryAfter());
        HttpStatus responseStatus = properties.getBlockAdvice().isHttpStatusAlign() ? targetStatus : HttpStatus.OK;
        String path = request != null ? request.getRequestURI() : "unknown";

        log.warn("Sentinel advice intercepted block/degrade: rule={}, code={}, status={}, path='{}', clientIp='{}', origin='{}', traceId='{}', skipCounter={}",
                ruleType, resultCode.getCode(), responseStatus.value(), path, clientIp, origin, traceId, skipCounter);

        return new ResponseEntity<>(Result.fail(resultCode.getCode(), resultCode.getMsg()), headers, responseStatus);
    }

    private String resolveResourceName(BlockException ex, HttpServletRequest request) {
        if (ex != null) {
            try {
                if (ex.getRule() != null && ex.getRule().getResource() != null) {
                    return ex.getRule().getResource();
                }
            } catch (Throwable ignored) {
                // 忽略 Sentinel 内部 rule.as(...) 空指针
            }
        }
        return request != null ? request.getRequestURI() : "unknown";
    }

    private BlockException extractBlockException(Throwable throwable) {
        return SentinelServletUtil.extractBlockException(throwable);
    }

    private BusinessException extractSentinelBusinessException(Throwable throwable) {
        return (BusinessException) SentinelServletUtil.findInCausalChain(
                throwable,
                candidate -> candidate instanceof BusinessException be
                        && SentinelServletUtil.resolveSentinelResultCode(be) != null);
    }
}
