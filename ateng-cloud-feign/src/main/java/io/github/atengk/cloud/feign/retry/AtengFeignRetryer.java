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
import java.util.concurrent.ThreadLocalRandom;

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

        // 4. 解析 Client 级别独立的重试配置与最大尝试次数 (优先匹配 contextId，其次匹配 name)
        String clientName = resolveClientName(e);
        AtengFeignRetryProperties retryProps = resolveRetryProperties(e, clientName);

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
            interval = calculateSleepInterval(effectivePeriod, effectiveMaxPeriod, attempt);
        }

        log.warn("Feign 客户端 [{}] 请求 [{}] 遭遇建连异常，正在进行第 {}/{} 次弹性重试，退避休眠 {} ms...",
                clientName, method, attempt - 1, effectiveMaxAttempts - 1, interval);

        try {
            Thread.sleep(interval);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * 计算指数退避休眠间隔（基于带保底下限的 Full Jitter 随机抖动算法）
     * 避免集群在大规模建连故障恢复时发生重试惊群与波峰叠加效应。
     *
     * @param effectivePeriod    基础间隔（毫秒）
     * @param effectiveMaxPeriod 最大上限间隔（毫秒）
     * @param attempt            当前重试次数序号（从 1 开始）
     * @return 实际退避休眠毫秒数
     */
    long calculateSleepInterval(long effectivePeriod, long effectiveMaxPeriod, int attempt) {
        long upper = (long) (effectivePeriod * Math.pow(1.5, attempt - 1));
        upper = Math.min(upper, effectiveMaxPeriod);
        long floor = Math.min(effectivePeriod / 2, 20);
        floor = Math.min(floor, upper);
        if (upper <= floor) {
            return upper;
        }
        return floor + ThreadLocalRandom.current().nextLong(upper - floor + 1);
    }

    /**
     * 判断异常因果链中是否包含网络建连超时或端口不可达
     */
    protected boolean isConnectTimeout(Throwable t) {
        return hasCause(t, current -> {
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
                    return !lower.contains("read timed out") && !lower.contains("response timeout");
                }
            }
            return false;
        });
    }

    /**
     * 判断异常因果链中是否包含读取超时
     */
    protected boolean isReadTimeout(Throwable t) {
        // Apache HttpClient 5 的 ConnectTimeoutException 继承自 SocketTimeoutException，前置排除
        if (isConnectTimeout(t)) {
            return false;
        }
        return hasCause(t, current -> {
            if (current instanceof SocketTimeoutException) {
                String msg = current.getMessage();
                return msg == null || !msg.toLowerCase().contains("connect");
            }
            if (current instanceof org.apache.hc.core5.http.ConnectionClosedException
                    || current instanceof org.apache.hc.core5.http.NoHttpResponseException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                return lower.contains("read timed out")
                        || lower.contains("response timeout")
                        || lower.contains("socket read timeout")
                        || lower.contains("read timeout");
            }
            return false;
        });
    }

    private static boolean hasCause(Throwable t, java.util.function.Predicate<Throwable> predicate) {
        Throwable current = t;
        while (current != null) {
            if (predicate.test(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Target<?> extractFeignTarget(RetryableException e) {
        if (e != null && e.request() != null && e.request().requestTemplate() != null) {
            return e.request().requestTemplate().feignTarget();
        }
        return null;
    }

    private String resolveClientName(RetryableException e) {
        Target<?> target = extractFeignTarget(e);
        if (target != null) {
            Class<?> type = target.type();
            if (type != null && type.isAnnotationPresent(org.springframework.cloud.openfeign.FeignClient.class)) {
                var feignClient = type.getAnnotation(org.springframework.cloud.openfeign.FeignClient.class);
                if (feignClient.contextId() != null && !feignClient.contextId().isBlank()) {
                    return feignClient.contextId();
                }
                if (feignClient.value() != null && !feignClient.value().isBlank()) {
                    return feignClient.value();
                }
                if (feignClient.name() != null && !feignClient.name().isBlank()) {
                    return feignClient.name();
                }
            }
            if (target.name() != null) {
                return target.name();
            }
        }
        return null;
    }

    private AtengFeignRetryProperties resolveRetryProperties(RetryableException e, String clientName) {
        if (properties == null || properties.getClients() == null) {
            return properties != null ? properties.getRetry() : null;
        }
        // 1. 优先按解析出的 clientName (contextId 优先) 匹配配置
        if (clientName != null && properties.getClients().containsKey(clientName)) {
            AtengFeignClientProperties clientProps = properties.getClients().get(clientName);
            if (clientProps != null && clientProps.getRetry() != null) {
                return clientProps.getRetry();
            }
        }
        // 2. 其次按 target.name() 匹配配置
        Target<?> target = extractFeignTarget(e);
        if (target != null && target.name() != null && properties.getClients().containsKey(target.name())) {
            AtengFeignClientProperties clientProps = properties.getClients().get(target.name());
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
