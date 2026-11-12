package io.github.atengk.cloud.gateway.provider;

import io.github.atengk.cloud.gateway.spi.GatewayNonceValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于内存滑动窗口的有界 Nonce 防重放校验器默认实现
 *
 * <p>支持配置最大缓存容量上限（默认 {@code 100,000}）：当触发清理水位或容量上限时优先驱逐过期条目，
 * 若清理后仍达上限则按 Fail-Closed 策略拒绝新 Nonce，防止高频随机 Nonce 洪泛攻击导致内存 OOM。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class InMemoryGatewayNonceValidator implements GatewayNonceValidator {

    private static final Logger log = LoggerFactory.getLogger(InMemoryGatewayNonceValidator.class);

    public static final int DEFAULT_MAX_CAPACITY = 100_000;
    private static final int DEFAULT_CLEANUP_THRESHOLD = 5_000;
    private static final long DEFAULT_CLEANUP_INTERVAL_MILLIS = 1_000L;
    private static final long DEFAULT_TTL_MILLIS = 300_000L;

    private final Map<String, NonceRecord> nonceCache = new ConcurrentHashMap<>();
    private final ReentrantLock cacheLock = new ReentrantLock();
    private final int maxCapacity;
    private final int cleanupThreshold;
    private long lastCleanupTimeMillis = 0L;

    public InMemoryGatewayNonceValidator() {
        this(DEFAULT_MAX_CAPACITY);
    }

    public InMemoryGatewayNonceValidator(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("nonceMaxCapacity 必须大于 0");
        }
        this.maxCapacity = maxCapacity;
        this.cleanupThreshold = Math.min(DEFAULT_CLEANUP_THRESHOLD, maxCapacity);
    }

    @Override
    public boolean validateAndStore(String nonce, Duration ttl) {
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        String normalizedNonce = nonce.trim();
        long now = System.currentTimeMillis();

        // 1. 无锁前置快速失败：若既有 Nonce 存在且未过期，立即判定为重放攻击，阻断对同步锁的无效竞争
        NonceRecord existingRecord = nonceCache.get(normalizedNonce);
        if (existingRecord != null && now <= existingRecord.expireAt()) {
            return false;
        }

        long allowedMs = (ttl != null && !ttl.isNegative()) ? ttl.toMillis() : DEFAULT_TTL_MILLIS;
        long expireAt = now + allowedMs * 2;

        cacheLock.lock();
        try {
            // 2. 启发式双重清理：达到硬上限或在超过清理阈值且间隔超时时执行过期驱逐，避免每次请求全表扫描加剧锁竞争
            boolean reachCapacity = nonceCache.size() >= maxCapacity;
            boolean reachThresholdAndInterval = nonceCache.size() >= cleanupThreshold
                    && (now - lastCleanupTimeMillis >= DEFAULT_CLEANUP_INTERVAL_MILLIS);

            if (reachCapacity || reachThresholdAndInterval) {
                nonceCache.entrySet().removeIf(entry -> now > entry.getValue().expireAt());
                lastCleanupTimeMillis = now;
            }

            // 3. 锁内二次检查防并发重复写入
            existingRecord = nonceCache.get(normalizedNonce);
            if (existingRecord != null) {
                if (now <= existingRecord.expireAt()) {
                    return false;
                }
                nonceCache.remove(normalizedNonce);
            }

            // 4. 因上方已优先驱逐过期条目；若仍满载则执行 Fail-Closed
            if (nonceCache.size() >= maxCapacity) {
                log.warn("[GatewaySign] 内存 Nonce 防重放缓存已达容量上限且无过期条目可驱逐，执行 Fail-Closed 拒绝: maxCapacity={}",
                        maxCapacity);
                return false;
            }

            nonceCache.put(normalizedNonce, new NonceRecord(expireAt));
            return true;
        } finally {
            if (cacheLock.isHeldByCurrentThread()) {
                cacheLock.unlock();
            }
        }
    }

    /**
     * Nonce 缓存元数据记录
     *
     * @param expireAt 过期时间戳（毫秒）
     */
    private record NonceRecord(long expireAt) {
    }
}
