package io.github.atengk.cloud.dubbo.handler;

import io.github.atengk.boot.core.exception.BusinessException;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.remoting.http12.HttpResult;
import org.apache.dubbo.remoting.http12.RequestMetadata;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dubbo Triple 协议 BusinessException 受管业务异常保真处理器
 * <p>
 * 在 Triple (gRPC/HTTP2) 传输层：
 * 1. 显式标记 grpc-status 为 0 (OK)，确保客户端不被底层 gRPC 传输层 UNKNOWN 错误中断；
 * 2. 将 {@link BusinessException} 打包入 HttpResult 响应载荷，使消费端 100% 因果保真还原业务异常与状态码。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate(order = -500)
public class DubboBusinessExceptionHandler extends AbstractDubboTripleExceptionHandler<BusinessException> {

    private static final Logger log = LoggerFactory.getLogger(DubboBusinessExceptionHandler.class);

    @Override
    protected Object doHandleGrpc(BusinessException throwable, RequestMetadata metadata, MethodDescriptor descriptor) {
        if (log.isDebugEnabled()) {
            log.debug("[DUBBO] DubboBusinessExceptionHandler handleGrpc: code={}, msg={}",
                    throwable.getCode(), throwable.getMessage());
        }
        return HttpResult.of(throwable);
    }
}
