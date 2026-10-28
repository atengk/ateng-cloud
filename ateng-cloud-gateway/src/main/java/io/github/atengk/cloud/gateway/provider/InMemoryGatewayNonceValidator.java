package io.github.atengk.cloud.gateway.provider;

import io.github.atengk.cloud.gateway.spi.GatewayNonceValidator;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存滑动窗口的 Nonce 防重放校验器默认实现
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class InMemoryGatewayNonceValidator implements GatewayNonceValidator {

    private final Map<String, Long> nonceCache = new ConcurrentHashMap<>();

    @Override
    public boolean validateAndStore(String nonce, Duration ttl) {
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long allowedMs = ttl != null ? ttl.toMillis() : 300_000L;

        // 定期清洗过期记录，防止内存无界膨胀
        if (nonceCache.size() > 5000) {
            nonceCache.entrySet().removeIf(entry -> now - entry.getValue() > allowedMs * 2);
        }

        Long previous = nonceCache.putIfAbsent(nonce.trim(), now);
        return previous == null;
    }
}
