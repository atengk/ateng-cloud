package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.properties.AtengDubboContractProperties;
import io.github.atengk.cloud.dubbo.support.DubboAttachmentSupport;
import io.github.atengk.cloud.dubbo.support.DubboExceptionUtils;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.BaseFilter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.support.RpcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Dubbo 消费端双模契约校验与业务结果自动断言过滤器
 * <p>
 * 1. 当 RPC 接口方法声明返回 {@code Result<?>} 且对端返回业务失败状态 (code != 200) 或空壳 {@code null}（受 {@code assertNonNullResult} 控制）时，
 *    自动转译为受管 {@link BusinessException} 抛出；
 * 2. 当 RPC 接口方法声明返回非 {@code void} 的 Java 基本类型且对端返回 {@code null} 时，在动态代理拆箱前立即拦截并转译为受管 {@link BusinessException}，
 *    彻底消除消费端裸 {@link NullPointerException}；
 * 3. 若接口声明返回原生 POJO 或业务成功 (code == 200)，则原样放行。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate(group = CommonConstants.CONSUMER, order = 1000)
public class DubboContractAssertFilter implements Filter, BaseFilter.Listener {

    private static final Logger log = LoggerFactory.getLogger(DubboContractAssertFilter.class);

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        return invoker.invoke(invocation);
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        var properties = DubboPropertiesHolder.get();
        AtengDubboContractProperties contractProps = properties != null ? properties.getContract() : null;
        if (properties != null && (!properties.isEnabled()
                || (contractProps != null && !contractProps.isEnabled()))) {
            return;
        }

        // 豁免流式调用与泛化调用
        if (DubboExceptionUtils.isStreamInvocation(invocation)
                || DubboExceptionUtils.isGenericInvocation(invoker, invocation)) {
            return;
        }

        if (appResponse == null || appResponse.hasException()) {
            return;
        }

        Object value = appResponse.getValue();
        if (value == null) {
            handleNullResponseValue(appResponse, invoker, invocation, contractProps);
            return;
        }

        if (value instanceof io.github.atengk.boot.core.model.Result<?> result && !result.isSuccess()) {
            int code = result.getCode() != null ? result.getCode() : CommonResultCode.INTERNAL_SERVER_ERROR.getCode();
            String msg = result.getMsg() != null ? result.getMsg() : "RPC 远端业务调用失败";
            log.warn("[DUBBO] 消费端拦截到非 200 业务结果: code={}, msg={}, service={}, method={}",
                    code, msg,
                    DubboAttachmentSupport.resolveServiceName(invoker),
                    DubboExceptionUtils.resolveMethodName(invocation));
            failWithBusinessException(appResponse, new BusinessException(code, msg));
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        // 异常链路由异常过滤器与底层 RPC 驱动处理
    }

    private void handleNullResponseValue(Result appResponse, Invoker<?> invoker, Invocation invocation,
                                         AtengDubboContractProperties contractProps) {
        Class<?> returnType = resolveReturnType(invoker, invocation);
        if (returnType == null) {
            return;
        }

        String serviceName = DubboAttachmentSupport.resolveServiceName(invoker);
        String methodName = DubboExceptionUtils.resolveMethodName(invocation);

        // 1. 基本类型返回 null 前置防拆箱 NPE 拦截 (排除 void.class，其 isPrimitive() 为 true)
        if (returnType != void.class && returnType.isPrimitive()) {
            log.warn("[DUBBO] 消费端拦截到基本类型空返回值防拆箱 NPE: returnType={}, service={}, method={}",
                    returnType.getName(), serviceName, methodName);
            failWithBusinessException(appResponse, new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "RPC 契约违规: 基本类型返回值 [" + returnType.getSimpleName() + "] 不允许为 null"
            ));
            return;
        }

        // 2. Result<T> 空壳返回断言拦截
        boolean assertNonNullResult = contractProps == null || contractProps.isAssertNonNullResult();
        if (assertNonNullResult && io.github.atengk.boot.core.model.Result.class.isAssignableFrom(returnType)) {
            log.warn("[DUBBO] 消费端拦截到 Result<?> 空壳返回值: service={}, method={}", serviceName, methodName);
            failWithBusinessException(appResponse, new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "RPC 契约违规: Result<?> 响应对象不允许为 null"
            ));
        }
    }

    private void failWithBusinessException(Result appResponse, BusinessException exception) {
        appResponse.setException(exception);
        appResponse.setValue(null);
    }

    private Class<?> resolveReturnType(Invoker<?> invoker, Invocation invocation) {
        if (invocation instanceof RpcInvocation rpcInvocation && rpcInvocation.getReturnType() != null) {
            return rpcInvocation.getReturnType();
        }
        Class<?> returnType = RpcUtils.getReturnType(invocation);
        if (returnType != null) {
            return returnType;
        }
        if (invoker == null || invoker.getInterface() == null || invocation == null) {
            return null;
        }
        String methodName = RpcUtils.getMethodName(invocation);
        if (methodName == null || methodName.isEmpty() || methodName.startsWith("$")) {
            return null;
        }
        Class<?>[] parameterTypes = invocation.getParameterTypes();
        try {
            Method method = invoker.getInterface().getMethod(
                    methodName, parameterTypes != null ? parameterTypes : new Class<?>[0]);
            return method.getReturnType();
        } catch (NoSuchMethodException e) {
            log.debug("[DUBBO] 未能在接口中定位方法返回类型: service={}, method={}",
                    invoker.getInterface().getName(), methodName);
            return null;
        }
    }
}
