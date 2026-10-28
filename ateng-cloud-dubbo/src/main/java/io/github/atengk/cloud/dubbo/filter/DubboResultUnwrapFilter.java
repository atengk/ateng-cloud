package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.BaseFilter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dubbo 消费端双模契约校验与 Result 自动断言过滤器
 * <p>
 * 当 RPC 接口方法声明返回 {@code Result<?>} 时，自动校验对端业务状态码：
 * 若非成功状态 (code != 200)，自动剥壳转译为受管 {@link BusinessException} 抛出，
 * 守住业务正确性防线，彻底消除消费端漏判 isSuccess 的隐患；
 * 若接口声明返回原生 POJO 或已返回业务异常，则原样放行。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate(group = CommonConstants.CONSUMER, order = 1000)
public class DubboResultUnwrapFilter implements Filter, BaseFilter.Listener {

    private static final Logger log = LoggerFactory.getLogger(DubboResultUnwrapFilter.class);

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        return invoker.invoke(invocation);
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        var properties = DubboPropertiesHolder.get();
        if (properties != null && properties.getResultUnwrap() != null && !properties.getResultUnwrap().isEnabled()) {
            return;
        }

        if (appResponse.hasException()) {
            return;
        }

        Object value = appResponse.getValue();
        if (value instanceof io.github.atengk.boot.core.model.Result<?> result) {
            if (!result.isSuccess()) {
                int code = result.getCode() != null ? result.getCode() : CommonResultCode.INTERNAL_SERVER_ERROR.getCode();
                String msg = result.getMsg() != null ? result.getMsg() : "RPC 远端业务调用失败";
                log.warn("[DUBBO] 消费端拦截到非 200 业务结果: code={}, msg={}, service={}, method={}",
                        code, msg, invoker.getInterface().getName(), invocation.getMethodName());
                BusinessException businessException = new BusinessException(code, msg);
                appResponse.setException(businessException);
                appResponse.setValue(null);
            }
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        // 异常链路由异常过滤器与底层 RPC 驱动处理
    }
}
