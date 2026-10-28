package io.github.atengk.cloud.dubbo.handler;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.support.DubboExceptionUtils;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.remoting.http12.HttpResult;
import org.apache.dubbo.remoting.http12.RequestMetadata;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

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
public class DubboValidationExceptionHandler extends AbstractDubboTripleExceptionHandler<ConstraintViolationException> {

    private static final Logger log = LoggerFactory.getLogger(DubboValidationExceptionHandler.class);

    @Override
    protected Object doHandleGrpc(ConstraintViolationException throwable, RequestMetadata metadata, MethodDescriptor descriptor) {
        Set<ConstraintViolation<?>> violations = throwable.getConstraintViolations();
        String errorMsg = (violations == null || violations.isEmpty())
                ? (throwable.getMessage() != null ? throwable.getMessage() : "参数校验失败")
                : DubboExceptionUtils.formatConstraintViolations(violations);
        log.warn("[DUBBO] Triple 传输层捕获参数校验异常，安全转译为 BAD_REQUEST: {}", errorMsg);
        BusinessException businessException = new BusinessException(
                CommonResultCode.BAD_REQUEST.getCode(),
                errorMsg,
                throwable
        );
        return HttpResult.of(businessException);
    }
}
