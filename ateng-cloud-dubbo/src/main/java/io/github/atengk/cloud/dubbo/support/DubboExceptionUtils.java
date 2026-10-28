package io.github.atengk.cloud.dubbo.support;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * Dubbo 异常研判与格式化公共工具类
 *
 * @author Ateng
 * @since 2026-10-28
 */
public final class DubboExceptionUtils {

    private DubboExceptionUtils() {
    }

    /**
     * 判断是否为线程池过载拒绝异常
     *
     * @param throwable 待检测的异常
     * @return 是否为过载异常
     */
    public static boolean isOverloadException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RejectedExecutionException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("thread pool is exhausted")
                        || lower.contains("threadpool is exhausted")
                        || lower.contains("threads can not be used anymore")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 沿着异常链路深度探测查找 ConstraintViolationException
     *
     * @param throwable 待检测的异常
     * @return ConstraintViolationException 实例，未找到返回 null
     */
    public static ConstraintViolationException findConstraintViolationException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException cve) {
                return cve;
            }
            if ("jakarta.validation.ConstraintViolationException".equals(current.getClass().getName())
                    || "javax.validation.ConstraintViolationException".equals(current.getClass().getName())) {
                try {
                    if (current instanceof ConstraintViolationException cve) {
                        return cve;
                    }
                } catch (Exception ignored) {
                }
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 格式化 JSR-303 参数校验违规集合为结构化文本
     *
     * @param violations 校验违规集合
     * @return 格式化后的错误描述
     */
    public static String formatConstraintViolations(Set<? extends ConstraintViolation<?>> violations) {
        if (violations == null || violations.isEmpty()) {
            return "参数校验失败";
        }
        return violations.stream()
                .map(v -> {
                    String path = v.getPropertyPath() != null ? v.getPropertyPath().toString() : "";
                    int dotIdx = path.lastIndexOf('.');
                    String field = dotIdx >= 0 ? path.substring(dotIdx + 1) : path;
                    return field + " " + v.getMessage();
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
