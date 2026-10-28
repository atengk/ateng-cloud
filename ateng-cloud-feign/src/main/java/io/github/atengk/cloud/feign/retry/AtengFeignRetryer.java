package io.github.atengk.cloud.feign.retry;

import feign.Request;
import feign.RetryableException;
import feign.Retryer;
import feign.Target;
import io.github.atengk.cloud.feign.properties.AtengFeignClientProperties;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import io.github.atengk.cloud.feign.properties.AtengFeignRetryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Feign 防雪崩弹性重试器
 * 严格践行“写请求绝对禁止重试”与“读超时禁重试”架构铁律：
 * 1. 对于非幂等写请求（POST / PUT / PATCH / DELETE），发生超时等任何异常坚决严禁重试，直接抛出阻断；
 * 2. 对于读超时（SocketTimeoutException / Read timed out），坚决禁止重试以防对端满载演变为全链路重试雪崩；
 * 3. 仅对纯读幂等请求（GET / HEAD）在网络建连握手超时（ConnectTimeoutException）或服务端口不可达（ConnectException）时允许弹性重试 1 次；
 * 4. 支持单 Client 物理隔离与动态继承覆盖。
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignRetryer implements Retryer {

    private static final Logger log = LoggerFactory.getLogger(AtengFeignRetryer.class);

    private final long period;
    private final long maxPeriod;
    private final int maxAttempts;
    private final AtengFeignProperties properties;

    int attempt;
    long sleptForMillis;

    public AtengFeignRetryer() {
        this(100, SECONDS.toMillis(1), 2, null);
    }

    public AtengFeignRetryer(long period, long maxPeriod, int maxAttempts) {
        this(period, maxPeriod, maxAttempts, null);
    }

    public AtengFeignRetryer(AtengFeignProperties properties) {
        this(
                properties != null && properties.getRetry() != null && properties.getRetry().getPeriod() != null
                        ? properties.getRetry().getPeriod().toMillis() : 100,
                properties != null && properties.getRetry() != null && properties.getRetry().getMaxPeriod() != null
                        ? properties.getRetry().getMaxPeriod().toMillis() : SECONDS.toMillis(1),
                properties != null && properties.getRetry() != null
                        ? properties.getRetry().getMaxAttempts() : 2,
                properties
        );
    }

    public AtengFeignRetryer(long period, long maxPeriod, int maxAttempts, AtengFeignProperties properties) {
        this.period = period;
        this.maxPeriod = maxPeriod;
        this.maxAttempts = maxAttempts;
        this.properties = properties;
        this.attempt = 1;
    }

    @Override
    public void continueOrPropagate(RetryableException e) {
        // 1. 严格拦截非幂等写请求 (POST / PUT / PATCH / DELETE)，杜绝下游重复落库与资损
        Request.HttpMethod method = e.method();
        if (method != Request.HttpMethod.GET && method != Request.HttpMethod.HEAD) {
            log.debug("Feign 请求 [{}] 属于非幂等写接口，严禁重试防资损", method);
            throw e;
        }

        // 2. 读超时绝对严禁重试，杜绝下游服务高负荷时演变为重试风暴
        if (isReadTimeout(e)) {
            log.debug("Feign 请求 [{}] 发生读超时，严禁重试防雪崩", method);
            throw e;
        }

        // 3. 仅限握手建连超时或网络端口不可达时允许弹性重试
        if (!isConnectTimeout(e)) {
            log.debug("Feign 请求 [{}] 发生非建连异常，不触发弹性重试", method);
            throw e;
        }

        // 4. 解析 Client 级别独立的重试配置与最大尝试次数
        String clientName = resolveClientName(e);
        AtengFeignRetryProperties retryProps = resolveRetryProperties(clientName);

        if (retryProps != null && !retryProps.isEnabled()) {
            log.debug("Feign 客户端 [{}] 重试机制已显式禁用", clientName);
            throw e;
        }

        int effectiveMaxAttempts = retryProps != null ? retryProps.getMaxAttempts() : this.maxAttempts;
        if (attempt++ >= effectiveMaxAttempts) {
            log.warn("Feign 客户端 [{}] 请求 [{}] 已达最大重试上限 ({}/{})，阻断向外抛出异常",
                    clientName, method, attempt - 1, effectiveMaxAttempts);
            throw e;
        }

        // 5. 按照指数退避策略计算休眠间隔
        long effectivePeriod = retryProps != null && retryProps.getPeriod() != null
                ? retryProps.getPeriod().toMillis() : this.period;
        long effectiveMaxPeriod = retryProps != null && retryProps.getMaxPeriod() != null
                ? retryProps.getMaxPeriod().toMillis() : this.maxPeriod;

        long interval;
        if (e.retryAfter() != null) {
            interval = e.retryAfter() - currentTimeMillis();
            if (interval > effectiveMaxPeriod) {
                interval = effectiveMaxPeriod;
            }
            if (interval < 0) {
                return;
            }
        } else {
            interval = (long) (effectivePeriod * Math.pow(1.5, attempt - 1));
            interval = Math.min(interval, effectiveMaxPeriod);
        }

        log.warn("Feign 客户端 [{}] 请求 [{}] 遭遇建连异常，正在进行第 {}/{} 次弹性重试，退避休眠 {} ms...",
                clientName, method, attempt - 1, effectiveMaxAttempts - 1, interval);

        try {
            Thread.sleep(interval);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw e;
        }
        sleptForMillis += interval;
    }

    /**
     * 判断异常因果链中是否包含网络建连超时或端口不可达
     */
    protected boolean isConnectTimeout(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof org.apache.hc.client5.http.ConnectTimeoutException
                    || current instanceof ConnectException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("connect timed out")
                        || lower.contains("connection refused")
                        || lower.contains("connect to")) {
                    if (!lower.contains("read timed out") && !lower.contains("response timeout")) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 判断异常因果链中是否包含读取超时
     */
    protected boolean isReadTimeout(Throwable t) {
        Throwable current = t;
        while (current != null) {
            // Apache HttpClient 5 的 ConnectTimeoutException 继承自 SocketTimeoutException，此处必须前置排除
            if (current instanceof org.apache.hc.client5.http.ConnectTimeoutException) {
                current = current.getCause();
                continue;
            }
            if (current instanceof SocketTimeoutException) {
                String msg = current.getMessage();
                if (msg == null || !msg.toLowerCase().contains("connect")) {
                    return true;
                }
            }
            if (current instanceof org.apache.hc.core5.http.ConnectionClosedException
                    || current instanceof org.apache.hc.core5.http.NoHttpResponseException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("read timed out")
                        || lower.contains("response timeout")
                        || lower.contains("socket read timeout")
                        || lower.contains("read timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String resolveClientName(RetryableException e) {
        if (e.request() != null && e.request().requestTemplate() != null) {
            Target<?> target = e.request().requestTemplate().feignTarget();
            if (target != null && target.name() != null) {
                return target.name();
            }
        }
        return null;
    }

    private AtengFeignRetryProperties resolveRetryProperties(String clientName) {
        if (properties == null) {
            return null;
        }
        if (clientName != null && properties.getClients() != null) {
            AtengFeignClientProperties clientProps = properties.getClients().get(clientName);
            if (clientProps != null && clientProps.getRetry() != null) {
                return clientProps.getRetry();
            }
        }
        return properties.getRetry();
    }

    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public int getAttempt() {
        return attempt;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getPeriod() {
        return period;
    }

    public long getMaxPeriod() {
        return maxPeriod;
    }

    @Override
    public Retryer clone() {
        return new AtengFeignRetryer(period, maxPeriod, maxAttempts, properties);
    }
}
