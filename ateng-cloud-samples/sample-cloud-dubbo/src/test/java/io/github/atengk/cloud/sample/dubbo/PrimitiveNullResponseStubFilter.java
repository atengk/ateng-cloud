package io.github.atengk.cloud.sample.dubbo;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 * 测试专用 Provider 侧空返回值注入过滤器，仅针对 {@code PrimitiveNullConsumerService#getNullCount}
 * 将返回值替换为 {@code null}，用于真实复现跨进程对端异常返回 {@code null} 时消费端基本类型防拆箱 NPE 契约校验。
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Activate(group = CommonConstants.PROVIDER, order = -30000)
public class PrimitiveNullResponseStubFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        if (invoker == null) {
            throw new IllegalArgumentException("invoker must not be null");
        }
        if (invoker.getInterface() == null || invocation == null) {
            return invoker.invoke(invocation);
        }
        String targetInterface = ContractAndExceptionDubboRpcTest.PrimitiveNullConsumerService.class.getName();
        if (targetInterface.equals(invoker.getInterface().getName())
                && "getNullCount".equals(invocation.getMethodName())) {
            invoker.invoke(invocation);
            return AsyncRpcResult.newDefaultAsyncResult((Object) null, invocation);
        }
        return invoker.invoke(invocation);
    }
}
