package io.github.atengk.cloud.dubbo.support;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.rpc.Constants;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.RpcUtils;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Dubbo 异常研判与格式化公共工具类
 *
 * @author Ateng
 * @since 2026-10-28
 */
public final class DubboExceptionUtils {

    /**
     * 网关/RPC 调用超时标准状态码 (504 GATEWAY_TIMEOUT)
     */
    public static final int GATEWAY_TIMEOUT_CODE = 504;

    private static final int MAX_CAUSE_DEPTH = 32;

    private DubboExceptionUtils() {
    }

    /**
     * 判断是否为线程池过载拒绝异常
     *
     * @param throwable 待检测的异常
     * @return 是否为过载异常
     */
    public static boolean isOverloadException(Throwable throwable) {
        return hasCause(throwable, current -> {
            if (current instanceof RejectedExecutionException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                return lower.contains("thread pool is exhausted")
                        || lower.contains("threadpool is exhausted")
                        || lower.contains("threads can not be used anymore");
            }
            return false;
        });
    }

    /**
     * 沿着异常链路深度探测查找 ConstraintViolationException
     *
     * @param throwable 待检测的异常
     * @return ConstraintViolationException 实例，未找到返回 null
     */
    public static ConstraintViolationException findConstraintViolationException(Throwable throwable) {
        return (ConstraintViolationException) findCause(throwable, ConstraintViolationException.class::isInstance);
    }

    /**
     * 判断当前消费端 Invoker URL 是否配置了有效原生 mock 参数
     *
     * @param invoker    Dubbo 调用器
     * @param invocation 当前 RPC 调用元数据
     * @return true 表示已配置有效 mock（如 true、fail:...、force:...、自定义 Mock 类名），false 表示未配置或为 false/null/0/N/A
     */
    public static boolean hasEffectiveMock(Invoker<?> invoker, Invocation invocation) {
        if (invoker == null || invoker.getUrl() == null) {
            return false;
        }
        URL url = invoker.getUrl();
        String methodName = invocation != null ? RpcUtils.getMethodName(invocation) : null;
        String mockValue = StringUtils.hasText(methodName)
                ? url.getMethodParameter(methodName, Constants.MOCK_KEY, Boolean.FALSE.toString())
                : url.getParameter(Constants.MOCK_KEY, Boolean.FALSE.toString());
        return ConfigUtils.isNotEmpty(mockValue != null ? mockValue.trim() : null);
    }

    /**
     * 判断是否为消费端 RPC 调用超时异常（支持穿透 Triple {@code ExecutionException -> StatusRpcException} 因果链）
     *
     * @param exception Dubbo 原生 RpcException
     * @return true 表示超时异常
     */
    public static boolean isTimeoutRpcException(RpcException exception) {
        if (exception == null) {
            return false;
        }
        return hasCause(exception, current -> current instanceof TimeoutException
                || current instanceof org.apache.dubbo.remoting.TimeoutException
                || (current instanceof RpcException nested
                && (nested.isTimeout() || nested.getCode() == RpcException.TIMEOUT_TERMINATE)));
    }

    /**
     * 判断是否为无可用提供者或路由拒绝类 RPC 异常
     *
     * @param exception Dubbo 原生 RpcException
     * @return true 表示无可用提供者/禁止访问
     */
    public static boolean isNoProviderAvailableException(RpcException exception) {
        if (exception == null) {
            return false;
        }
        return hasCause(exception, current -> current instanceof RpcException nested
                && (nested.isForbidden()
                || nested.isNoInvokerAvailableAfterFilter()
                || containsNoProviderMessage(nested.getMessage())));
    }

    /**
     * 判断是否为消费端网络不可达或传输层连接失败异常
     *
     * @param exception Dubbo 原生 RpcException
     * @return true 表示网络异常
     */
    public static boolean isNetworkRpcException(RpcException exception) {
        if (exception == null) {
            return false;
        }
        return hasCause(exception, current -> current instanceof RpcException nested && nested.isNetwork());
    }

    private static boolean containsNoProviderMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("no provider available")
                || lower.contains("no invoker available")
                || (lower.contains("upstream") && lower.contains("is unavailable"));
    }

    /**
     * 将消费端未开启 mock 的原生 {@link RpcException} 因果转译为受管 {@link BusinessException}
     * <p>
     * 状态码映射契约：
     * 1. 超时异常 ({@code TIMEOUT_EXCEPTION}) -> {@code 504 GATEWAY_TIMEOUT}；
     * 2. 网络异常 / 禁止访问 / 无可用提供者 -> {@code 503 SERVICE_UNAVAILABLE}；
     * 3. 线程池过载异常 -> {@code 429 TOO_MANY_REQUESTS}；
     * 4. 其他未知 {@link RpcException} -> {@code 500 INTERNAL_SERVER_ERROR}；
     * 100% 保留原始 {@link RpcException} 作为直接 {@code cause}。
     *
     * @param exception Dubbo 原生 RpcException
     * @return 携带原始因果链的受管 BusinessException
     */
    public static BusinessException translateConsumerRpcException(RpcException exception) {
        if (exception == null) {
            return new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "RPC 消费端调用发生未知异常");
        }
        String detail = StringUtils.hasText(exception.getMessage())
                ? exception.getMessage()
                : exception.getClass().getSimpleName();
        if (isOverloadException(exception)) {
            return new BusinessException(
                    CommonResultCode.TOO_MANY_REQUESTS.getCode(),
                    "服务端并发线程池已耗尽，触发过载保护限流",
                    exception
            );
        }
        if (isTimeoutRpcException(exception)) {
            return new BusinessException(
                    GATEWAY_TIMEOUT_CODE,
                    "RPC 调用超时 (GATEWAY_TIMEOUT): " + detail,
                    exception
            );
        }
        if (isNetworkRpcException(exception) || isNoProviderAvailableException(exception)) {
            return new BusinessException(
                    CommonResultCode.SERVICE_UNAVAILABLE.getCode(),
                    "RPC 下游服务不可用: " + detail,
                    exception
            );
        }
        return new BusinessException(
                CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                "RPC 消费端调用异常: " + detail,
                exception
        );
    }

    /**
     * 沿异常因果链匹配指定断言条件（内置环路防卫上限，避免自引用循环导致死循环）
     *
     * @param throwable 起始异常
     * @param predicate 匹配断言
     * @return 因果链上任意节点满足断言则返回 true
     */
    public static boolean hasCause(Throwable throwable, Predicate<Throwable> predicate) {
        return findCause(throwable, predicate) != null;
    }

    private static Throwable findCause(Throwable throwable, Predicate<Throwable> predicate) {
        if (throwable == null || predicate == null) {
            return null;
        }
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (predicate.test(current)) {
                return current;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    /**
     * 安全提取 RPC 调用方法名（空值或空白时回落为 {@code "unknown"}）
     *
     * @param invocation RPC 调用元数据
     * @return 非空方法名
     */
    public static String resolveMethodName(Invocation invocation) {
        if (invocation == null) {
            return "unknown";
        }
        String methodName = RpcUtils.getMethodName(invocation);
        return StringUtils.hasText(methodName) ? methodName : "unknown";
    }

    /**
     * 格式化 JSR-303 参数校验违规集合为结构化文本
     *
     * @param violations 校验违规集合
     * @return 格式化后的错误描述
     */
    public static String formatConstraintViolations(Set<? extends ConstraintViolation<?>> violations) {
        return formatConstraintViolations(violations, null);
    }

    /**
     * 格式化 JSR-303 参数校验违规集合为结构化文本（支持前置集合/数组索引或 Map 键路径）
     *
     * @param violations 校验违规集合
     * @param pathPrefix 所属容器元素路径前缀（如 {@code "[1]"} 或 {@code "[vip]"}，可为空）
     * @return 格式化后的错误描述
     */
    public static String formatConstraintViolations(Set<? extends ConstraintViolation<?>> violations, String pathPrefix) {
        if (violations == null || violations.isEmpty()) {
            return "参数校验失败";
        }
        String normalizedPrefix = StringUtils.hasText(pathPrefix) ? pathPrefix.trim() : "";
        return violations.stream()
                .map(v -> {
                    String path = v.getPropertyPath() != null ? v.getPropertyPath().toString() : "";
                    int dotIdx = path.lastIndexOf('.');
                    String field = dotIdx >= 0 ? path.substring(dotIdx + 1) : path;
                    String fullPath = normalizedPrefix.isEmpty()
                            ? field
                            : (StringUtils.hasText(field) ? normalizedPrefix + "." + field : normalizedPrefix);
                    return fullPath + " " + v.getMessage();
                })
                .collect(Collectors.joining("; ", "参数校验失败: ", ""));
    }

    /**
     * 判断指定类型是否为 Dubbo StreamObserver 流式观察者
     *
     * @param clazz 类对象
     * @return 是否为 StreamObserver
     */
    public static boolean isStreamObserver(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return "org.apache.dubbo.common.stream.StreamObserver".equals(clazz.getName())
                || "org.apache.dubbo.rpc.protocol.tri.observer.CallStreamObserver".equals(clazz.getName())
                || clazz.getName().contains("StreamObserver");
    }

    /**
     * 判断当前 RPC 调用是否为泛化调用 ({@link GenericService} 接口或 {@code $invoke}/{@code $invokeAsync} 方法)
     *
     * @param invoker    Dubbo 调用器
     * @param invocation RPC 调用元数据
     * @return true 表示为泛化调用
     */
    public static boolean isGenericInvocation(Invoker<?> invoker, Invocation invocation) {
        if (invoker != null && invoker.getInterface() != null
                && GenericService.class.isAssignableFrom(invoker.getInterface())) {
            return true;
        }
        if (invocation == null) {
            return false;
        }
        String methodName = RpcUtils.getMethodName(invocation);
        return CommonConstants.$INVOKE.equals(methodName)
                || CommonConstants.$INVOKE_ASYNC.equals(methodName);
    }

    /**
     * 判断当前 RPC 调用是否为 Triple 流式通信调用 (入参或返回值包含 StreamObserver)
     *
     * @param invocation RPC 调用元数据
     * @return 是否为流式通信调用
     */
    public static boolean isStreamInvocation(Invocation invocation) {
        if (invocation == null) {
            return false;
        }
        Class<?>[] parameterTypes = invocation.getParameterTypes();
        if (parameterTypes != null) {
            for (Class<?> paramType : parameterTypes) {
                if (isStreamObserver(paramType)) {
                    return true;
                }
            }
        }
        Class<?> returnType = RpcUtils.getReturnType(invocation);
        return isStreamObserver(returnType);
    }
}
