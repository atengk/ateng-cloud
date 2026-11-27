package io.github.atengk.cloud.gateway.provider;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.gateway.spi.GatewayNonceValidator;
import io.github.atengk.cloud.gateway.spi.ReactiveGatewayNonceValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Objects;

/**
 * 基于 {@link ReactiveStringRedisTemplate} 的非阻塞分布式 Nonce 防重放校验器
 *
 * <p>核心契约：
 * <ul>
 *     <li>使用原子 {@code setIfAbsent(key, "1", effectiveTtl)} 写入 Redis，零阻塞 Netty EventLoop；</li>
 *     <li>Key 按 {@code <noncePrefix><appId>:<nonce>} 隔离，杜绝跨应用 Nonce 碰撞；</li>
 *     <li>有效 TTL 取 {@code 2 * timestampThreshold}，完整覆盖正负对称时钟偏差窗口；</li>
 *     <li>Redis 异常时默认执行 {@code Fail-Closed} 阻断，显式开启 {@code nonceFailOpen=true} 时降级至有界内存校验。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class RedisReactiveGatewayNonceValidator implements ReactiveGatewayNonceValidator {

    private static final Logger log = LoggerFactory.getLogger(RedisReactiveGatewayNonceValidator.class);

    public static final String DEFAULT_NONCE_PREFIX = "ateng:gateway:sign:nonce:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final String noncePrefix;
    private final boolean failOpen;
    private final GatewayNonceValidator fallbackValidator;

    public RedisReactiveGatewayNonceValidator(ReactiveStringRedisTemplate redisTemplate,
                                              String noncePrefix,
                                              boolean failOpen,
                                              GatewayNonceValidator fallbackValidator) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "ReactiveStringRedisTemplate 不能为空");
        this.noncePrefix = StringUtils.hasText(noncePrefix) ? noncePrefix.trim() : DEFAULT_NONCE_PREFIX;
        this.failOpen = failOpen;
        this.fallbackValidator = failOpen
                ? (fallbackValidator != null ? fallbackValidator : new InMemoryGatewayNonceValidator())
                : fallbackValidator;
    }

    @Override
    public Mono<Boolean> validateAndStore(String appId, String nonce, Duration ttl) {
        if (!StringUtils.hasText(nonce)) {
            return Mono.just(false);
        }
        String safeAppId = StringUtils.hasText(appId) ? appId.trim() : "default";
        String normalizedNonce = nonce.trim();
        String redisKey = noncePrefix + safeAppId + ":" + normalizedNonce;
        Duration safeTtl = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : DEFAULT_TTL;
        Duration effectiveTtl = safeTtl.multipliedBy(2);

        return redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "1", effectiveTtl)
                .map(Boolean.TRUE::equals)
                .defaultIfEmpty(Boolean.FALSE)
                .onErrorResume(ex -> {
                    if (failOpen && fallbackValidator != null) {
                        log.warn("[GatewaySign] Redis Nonce 校验异常，因 nonceFailOpen=true 降级至本地有界内存校验: key={}",
                                redisKey, ex);
                        return Mono.fromCallable(() -> fallbackValidator.validateAndStore(
                                safeAppId + ":" + normalizedNonce, safeTtl))
                                .subscribeOn(Schedulers.boundedElastic());
                    }
                    return Mono.error(new BusinessException(
                            CommonResultCode.SERVICE_UNAVAILABLE, "防重放存储服务暂不可用", ex));
                });
    }
}
