package io.github.atengk.cloud.dubbo.handler;

import org.apache.dubbo.remoting.http12.ExceptionHandler;
import org.apache.dubbo.remoting.http12.HttpHeaders;
import org.apache.dubbo.remoting.http12.RequestMetadata;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.protocol.tri.h12.grpc.GrpcHeaderNames;

/**
 * Dubbo Triple 协议异常处理公共基类
 * <p>
 * 统一收敛 Triple 传输层异常状态码解析与响应体包装流程：
 * 1. 自动豁免流式调用 (非 UNARY 请求放行至 Triple 原生流式通道，确保客户端正常触发 onError 回调)；
 * 2. 对 UNARY 一元调用统一将 grpc-status 标记为 0 (OK)，彻底杜绝传输层因 UNKNOWN 错误直接断开连接；
 * 3. 模板方法 {@link #doHandleGrpc} 由子类实现具体的受管业务异常包装逻辑。
 *
 * @param <T> 异常类型
 * @author Ateng
 * @since 2026-10-28
 */
public abstract class AbstractDubboTripleExceptionHandler<T extends Throwable> implements ExceptionHandler<T, Object> {

    @Override
    public boolean resolveGrpcStatus(
            T throwable, HttpHeaders headers, RequestMetadata metadata, MethodDescriptor descriptor) {
        // 豁免流式调用 (非 UNARY 请求交由 Triple 原生流式异常通道处理，确保客户端正常触发 onError)
        if (isStreamingCall(descriptor)) {
            return false;
        }
        headers.set(GrpcHeaderNames.GRPC_STATUS.getName(), "0");
        return true;
    }

    @Override
    public Object handleGrpc(T throwable, RequestMetadata metadata, MethodDescriptor descriptor) {
        // 豁免流式调用 (非 UNARY 请求交由 Triple 原生流式异常通道处理)
        if (isStreamingCall(descriptor)) {
            return null;
        }
        return doHandleGrpc(throwable, metadata, descriptor);
    }

    /**
     * 判断当前调用是否为流式调用 (非 UNARY)
     *
     * @param descriptor 方法描述符
     * @return 是否为流式调用
     */
    protected boolean isStreamingCall(MethodDescriptor descriptor) {
        return descriptor != null && descriptor.getRpcType() != MethodDescriptor.RpcType.UNARY;
    }

    /**
     * 执行具体的 gRPC 异常响应载荷封装
     *
     * @param throwable 异常实例
     * @param metadata 请求元数据
     * @param descriptor 方法描述符
     * @return 响应结果对象
     */
    protected abstract Object doHandleGrpc(T throwable, RequestMetadata metadata, MethodDescriptor descriptor);
}
