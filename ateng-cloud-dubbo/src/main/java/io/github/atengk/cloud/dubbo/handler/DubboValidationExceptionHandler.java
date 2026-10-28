package io.github.atengk.cloud.dubbo.handler;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.remoting.http12.ExceptionHandler;
import org.apache.dubbo.remoting.http12.HttpHeaders;
import org.apache.dubbo.remoting.http12.HttpResult;
import org.apache.dubbo.remoting.http12.RequestMetadata;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.protocol.tri.h12.grpc.GrpcHeaderNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dubbo Triple 协议 ConstraintViolationException 参数校验保真处理器
 * <p>
 * 在 Triple (gRPC/HTTP2) 传输层拦截 JSR-303 参数校验失败异常：
 * 1. 显式标记 grpc-status 为 0 (OK)，保障客户端不发生传输层中断；
 * 2. 提取具体违规字段并格式化为清晰错误描述；
 * 3. 封装为标准受管 {@link BusinessException} (BAD_REQUEST / 400) 打包入 HttpResult 响应载荷。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate(order = -480)
public class DubboValidationExceptionHandler implements ExceptionHandler<ConstraintViolationException, Object> {

    private static final Logger log = LoggerFactory.getLogger(DubboValidationExceptionHandler.class);

    @Override
    public boolean resolveGrpcStatus(
            ConstraintViolationException throwable, HttpHeaders headers, RequestMetadata metadata, MethodDescriptor descriptor) {
        if (log.isDebugEnabled()) {
            log.debug("[DUBBO] DubboValidationExceptionHandler resolveGrpcStatus: msg={}", throwable.getMessage());
        }
        headers.set(GrpcHeaderNames.GRPC_STATUS.getName(), "0");
        return true;
    }

    @Override
    public Object handleGrpc(ConstraintViolationException throwable, RequestMetadata metadata, MethodDescriptor descriptor) {
        Set<ConstraintViolation<?>> violations = throwable.getConstraintViolations();
        String errorMsg;
        if (violations == null || violations.isEmpty()) {
            errorMsg = throwable.getMessage() != null ? throwable.getMessage() : "参数校验失败";
        } else {
            errorMsg = violations.stream()
                    .map(v -> {
                        String path = v.getPropertyPath() != null ? v.getPropertyPath().toString() : "";
                        int dotIdx = path.lastIndexOf('.');
                        String field = dotIdx >= 0 ? path.substring(dotIdx + 1) : path;
                        return field + " " + v.getMessage();
                    })
                    .collect(Collectors.joining("; ", "参数校验失败: ", ""));
        }
        log.warn("[DUBBO] Triple 传输层捕获参数校验异常，安全转译为 BAD_REQUEST: {}", errorMsg);
        BusinessException businessException = new BusinessException(
                CommonResultCode.BAD_REQUEST.getCode(),
                errorMsg,
                throwable
        );
        return HttpResult.of(businessException);
    }
}
