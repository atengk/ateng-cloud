package io.github.atengk.cloud.sentinel.advice;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.handler.AtengBlockExceptionHandler;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import io.github.atengk.cloud.sentinel.util.SentinelServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.lang.reflect.UndeclaredThrowableException;

/**
 * Sentinel {@code @SentinelResource} 未捕获异常全局切面兜底处理器
 * <p>
 * 专职捕获内部方法标注 {@code @SentinelResource} 且未配置 {@code blockHandler} 时抛出的
 * {@link BlockException} 及其被动态代理包装的 {@link UndeclaredThrowableException}，
 * 统一转译为 HTTP 429/503 与规范强类型 {@link Result} 响应体，杜绝未捕获异常转化为系统 500 虚假报警。
 *
 * @author Ateng
 * @since 2026-10-29
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class SentinelBlockExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(SentinelBlockExceptionAdvice.class);

    private final AtengSentinelProperties properties;

    public SentinelBlockExceptionAdvice(AtengSentinelProperties properties) {
        this.properties = properties != null ? properties : new AtengSentinelProperties();
    }

    /**
     * 直接捕获 BlockException 及其子类
     */
    @ExceptionHandler(BlockException.class)
    public ResponseEntity<Result<Void>> handleBlockException(BlockException ex, HttpServletRequest request) {
        return buildResponse(ex, request);
    }

    /**
     * 兼容捕获由动态代理包装的 UndeclaredThrowableException
     */
    @ExceptionHandler(UndeclaredThrowableException.class)
    public ResponseEntity<Result<Void>> handleUndeclaredThrowableException(UndeclaredThrowableException ex, HttpServletRequest request) throws Throwable {
        BlockException blockException = extractBlockException(ex);
        if (blockException != null) {
            return buildResponse(blockException, request);
        }
        // 非 Sentinel 异常放行，交给后续全局异常处理器处理
        throw ex;
    }

    /**
     * 构建统一错误响应与状态码
     */
    private ResponseEntity<Result<Void>> buildResponse(BlockException ex, HttpServletRequest request) {
        SentinelResultCode resultCode = AtengBlockExceptionHandler.determineResultCode(ex);
        HttpStatus targetStatus = AtengBlockExceptionHandler.determineHttpStatus(ex);

        boolean align = properties.getBlockAdvice().isHttpStatusAlign() && properties.getBlockHandler().isHttpStatusAlign();
        HttpStatus httpStatus = align ? targetStatus : HttpStatus.OK;

        String path = request != null ? request.getRequestURI() : "unknown";
        String clientIp = SentinelServletUtil.resolveClientIp(request);
        String resource = resolveResource(ex);

        log.warn("Sentinel @SentinelResource block triggered: resource='{}', rule={}, status={}, path='{}', clientIp='{}'",
                resource, ex.getClass().getSimpleName(), httpStatus.value(), path, clientIp);

        return ResponseEntity.status(httpStatus).body(Result.fail(resultCode));
    }

    /**
     * 防御性提取阻断资源名 (防范 Sentinel 部分子类在 rule 为 null 时 getRule() 内部抛出 NPE)
     */
    private String resolveResource(BlockException ex) {
        if (ex == null) {
            return "unknown";
        }
        try {
            if (ex.getRule() != null && ex.getRule().getResource() != null) {
                return ex.getRule().getResource();
            }
        } catch (Throwable ignored) {
            // 忽略 Sentinel 内部 rule.as(...) 空指针
        }
        return "unknown";
    }

    /**
     * 递归从异常链中提取底层的 BlockException
     */
    private BlockException extractBlockException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof BlockException be) {
                return be;
            }
            Throwable cause = current.getCause();
            if (current instanceof UndeclaredThrowableException ute && cause == null) {
                cause = ute.getUndeclaredThrowable();
            }
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return null;
    }
}
