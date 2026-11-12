package io.github.atengk.cloud.feign.support;

import feign.FeignException;
import feign.RetryableException;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Feign 异常分类映射与因果链转译支持类
 * <p>
 * 统一封装网络建连失败、读超时判定与 HTTP 状态码到 {@link IResultCode} 的标准映射，
 * 为错误解码器、弹性重试器及动态代理异常转译拦截器提供无重复的公共判定原语。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-12
 */
public final class FeignErrorSupport {

    private FeignErrorSupport() {
    }

    /**
     * 将 Feign HTTP 状态码映射为标准业务错误码
     *
     * @param status HTTP 状态码
     * @return 标准结果码
     */
    public static IResultCode mapHttpStatusToResultCode(int status) {
        return switch (status) {
            case 400 -> CommonResultCode.BAD_REQUEST;
            case 401 -> CommonResultCode.UNAUTHORIZED;
            case 403 -> CommonResultCode.FORBIDDEN;
            case 404 -> CommonResultCode.NOT_FOUND;
            case 405 -> CommonResultCode.METHOD_NOT_ALLOWED;
            case 413 -> CommonResultCode.PAYLOAD_TOO_LARGE;
            case 429 -> CommonResultCode.TOO_MANY_REQUESTS;
            case 503 -> CommonResultCode.SERVICE_UNAVAILABLE;
            default -> CommonResultCode.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * 判断异常因果链中是否包含网络建连超时或端口不可达
     *
     * @param throwable 待检测异常
     * @return true 表示属于建连阶段异常
     */
    public static boolean isConnectTimeoutCause(Throwable throwable) {
        return hasCause(throwable, current -> {
            if (current instanceof ConnectTimeoutException || current instanceof ConnectException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("connect timed out")
                        || lower.contains("connection refused")
                        || lower.contains("connect to")) {
                    return !lower.contains("read timed out") && !lower.contains("response timeout");
                }
            }
            return false;
        });
    }

    /**
     * 判断异常因果链中是否包含网络读取超时或连接中途断开
     *
     * @param throwable 待检测异常
     * @return true 表示属于读取超时或对端断连
     */
    public static boolean isReadTimeoutCause(Throwable throwable) {
        // Apache HttpClient 5 的 ConnectTimeoutException 继承自 SocketTimeoutException，前置排除
        if (isConnectTimeoutCause(throwable)) {
            return false;
        }
        return hasCause(throwable, current -> {
            if (current instanceof SocketTimeoutException) {
                String msg = current.getMessage();
                return msg == null || !msg.toLowerCase(Locale.ROOT).contains("connect");
            }
            if (current instanceof ConnectionClosedException || current instanceof NoHttpResponseException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                return lower.contains("read timed out")
                        || lower.contains("response timeout")
                        || lower.contains("socket read timeout")
                        || lower.contains("read timeout");
            }
            return false;
        });
    }

    /**
     * 判断异常因果链中是否包含网络层超时、建连失败、DNS 解析或底层套接字/TLS 通信故障
     * （显式排除 Jackson JSON 反序列化等非传输层 IOException 子类）
     *
     * @param throwable 待检测异常
     * @return true 表示属于网络层通信故障
     */
    public static boolean isTimeoutOrNetworkCause(Throwable throwable) {
        if (isConnectTimeoutCause(throwable) || isReadTimeoutCause(throwable)) {
            return true;
        }
        return hasCause(throwable, current -> current instanceof SocketException
                || current instanceof UnknownHostException
                || current instanceof SSLException
                || current instanceof ClosedChannelException);
    }

    /**
     * 将 {@link RetryableException} 或未转译 {@link FeignException} 因果转译为 {@link BusinessException}，
     * 100% 保留原始异常作为直接 {@code cause}
     *
     * @param methodKey 调用方法标识
     * @param exception 原始 Feign 异常
     * @return 携带原始因果链的受管业务异常
     */
    public static BusinessException translateFeignException(String methodKey, FeignException exception) {
        String methodLabel = StringUtils.hasText(methodKey) ? methodKey : "unknown";
        if (exception == null) {
            return new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    String.format("Feign 调用下游服务异常: [%s]", methodLabel));
        }
        IResultCode resultCode = resolveTranslatedResultCode(exception);
        String message = resultCode == CommonResultCode.SERVICE_UNAVAILABLE
                ? String.format("Feign 调用下游服务网络异常: [%s], 原始错误: %s", methodLabel, exception.getMessage())
                : String.format("Feign 调用下游服务异常: [%s], HTTP 状态码: %d", methodLabel, exception.status());
        return new BusinessException(resultCode, message, exception);
    }

    private static IResultCode resolveTranslatedResultCode(FeignException exception) {
        int status = exception.status();
        if (exception instanceof RetryableException) {
            if (status <= 0 || status == 503 || status == 504 || isTimeoutOrNetworkCause(exception)) {
                return CommonResultCode.SERVICE_UNAVAILABLE;
            }
            return mapHttpStatusToResultCode(status);
        }
        if (status == 504 || isTimeoutOrNetworkCause(exception)) {
            return CommonResultCode.SERVICE_UNAVAILABLE;
        }
        return mapHttpStatusToResultCode(status);
    }

    /**
     * 沿异常因果链匹配指定断言条件（内置环路防卫上限，避免自引用循环导致死循环）
     *
     * @param throwable 起始异常
     * @param predicate 匹配断言
     * @return 因果链上任意节点满足断言则返回 true
     */
    public static boolean hasCause(Throwable throwable, Predicate<Throwable> predicate) {
        if (throwable == null || predicate == null) {
            return false;
        }
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (predicate.test(current)) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }
}
