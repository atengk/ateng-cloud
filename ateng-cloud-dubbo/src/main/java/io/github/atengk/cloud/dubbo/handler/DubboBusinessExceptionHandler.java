package io.github.atengk.cloud.dubbo.handler;

import io.github.atengk.boot.core.exception.BusinessException;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.remoting.http12.ExceptionHandler;
import org.apache.dubbo.remoting.http12.HttpHeaders;
import org.apache.dubbo.remoting.http12.HttpResult;
import org.apache.dubbo.remoting.http12.RequestMetadata;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.protocol.tri.h12.grpc.GrpcHeaderNames;

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
public class DubboBusinessExceptionHandler implements ExceptionHandler<BusinessException, Object> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DubboBusinessExceptionHandler.class);

    @Override
    public boolean resolveGrpcStatus(
            BusinessException throwable, HttpHeaders headers, RequestMetadata metadata, MethodDescriptor descriptor) {
        // 豁免流式调用 (非 UNARY 请求交由 Triple 原生流式异常通道处理，确保客户端正常触发 onError)
        if (descriptor != null && descriptor.getRpcType() != MethodDescriptor.RpcType.UNARY) {
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug("[DUBBO] DubboBusinessExceptionHandler resolveGrpcStatus: code={}, msg={}", throwable.getCode(), throwable.getMessage());
        }
        headers.set(GrpcHeaderNames.GRPC_STATUS.getName(), "0");
        return true;
    }

    @Override
    public Object handleGrpc(BusinessException throwable, RequestMetadata metadata, MethodDescriptor descriptor) {
        if (descriptor != null && descriptor.getRpcType() != MethodDescriptor.RpcType.UNARY) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[DUBBO] DubboBusinessExceptionHandler handleGrpc: code={}, msg={}", throwable.getCode(), throwable.getMessage());
        }
        return HttpResult.of(throwable);
    }
}
