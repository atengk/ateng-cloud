package io.github.atengk.cloud.dubbo.handler;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.overload.DubboOverloadListener;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.remoting.http12.HttpResult;
import org.apache.dubbo.remoting.http12.RequestMetadata;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;

/**
 * Dubbo Triple 协议 RejectedExecutionException 线程池过载保护处理器
 * <p>
 * 在 Triple (gRPC/HTTP2) 传输层拦截线程池过载拒绝异常：
 * 1. 显式标记 grpc-status 为 0 (OK)，彻底杜绝底层传输连接异常断开；
 * 2. 递增 Micrometer 拒绝计数器与打印过载诊断日志；
 * 3. 将其转译为标准受管 {@link BusinessException} (TOO_MANY_REQUESTS / 429) 打包入 HttpResult 响应载荷。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate(order = -490)
public class DubboOverloadExceptionHandler extends AbstractDubboTripleExceptionHandler<RejectedExecutionException> {

    private static final Logger log = LoggerFactory.getLogger(DubboOverloadExceptionHandler.class);

    @Override
    protected Object doHandleGrpc(RejectedExecutionException throwable, RequestMetadata metadata, MethodDescriptor descriptor) {
        DubboOverloadListener.incrementRejectCount();
        log.warn("[DUBBO] Triple 传输层捕获线程池过载拒绝异常，安全转译为 TOO_MANY_REQUESTS: {}", throwable.getMessage());
        BusinessException businessException = new BusinessException(
                CommonResultCode.TOO_MANY_REQUESTS.getCode(),
                "服务端并发线程池已耗尽，触发过载保护限流",
                throwable
        );
        return HttpResult.of(businessException);
    }
}
