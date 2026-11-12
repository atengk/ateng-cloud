package io.github.atengk.cloud.gateway.provider;

import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 有界内存 Nonce 防重放校验器单元测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
@DisplayName("有界内存 Nonce 防重放校验器单元测试")
class InMemoryGatewayNonceValidatorTest {

    @Test
    @DisplayName("默认配置 nonceMaxCapacity 为 100000 且构造拒绝非正数容量")
    void testDefaultCapacityAndFailFastGuard() {
        AtengGatewaySignProperties properties = new AtengGatewaySignProperties();
        assertEquals(100_000, properties.getNonceMaxCapacity());
        properties.setNonceMaxCapacity(2048);
        assertEquals(2048, properties.getNonceMaxCapacity());

        assertThrows(IllegalArgumentException.class, () -> new InMemoryGatewayNonceValidator(0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryGatewayNonceValidator(-5));
    }

    @Test
    @DisplayName("达到容量上限且未过期时触发 Fail-Closed 拒绝新 Nonce 且绝不清空既有防重放记录")
    void testBoundedCapacityFailClosedPreservesReplayProtection() {
        InMemoryGatewayNonceValidator validator = new InMemoryGatewayNonceValidator(3);
        Duration ttl = Duration.ofMinutes(5);

        assertTrue(validator.validateAndStore("nonce-1", ttl));
        assertTrue(validator.validateAndStore("nonce-2", ttl));
        assertTrue(validator.validateAndStore("nonce-3", ttl));

        // 达到容量上限且全部未过期，第 4 个新 Nonce 必须 Fail-Closed 拒绝
        assertFalse(validator.validateAndStore("nonce-4", ttl), "满载未过期时应 Fail-Closed 拒绝新 Nonce");

        // 既有 Nonce 依然被严格拦截（绝不能清空全表导致重放漏洞）
        assertFalse(validator.validateAndStore("nonce-1", ttl), "既有 Nonce 防重放记录绝不可被清空");
        assertFalse(validator.validateAndStore("nonce-2", ttl));
        assertFalse(validator.validateAndStore("nonce-3", ttl));
    }

    @Test
    @DisplayName("达到容量上限时优先清理过期条目，过期释放空间后可正常接纳新 Nonce")
    void testExpiredEntriesEvictedBeforeFailClosed() throws Exception {
        InMemoryGatewayNonceValidator validator = new InMemoryGatewayNonceValidator(2);
        Duration shortTtl = Duration.ofMillis(25);

        assertTrue(validator.validateAndStore("exp-1", shortTtl));
        assertTrue(validator.validateAndStore("exp-2", shortTtl));
        assertFalse(validator.validateAndStore("overflow-before-expiry", shortTtl));

        // 超过 2 * ttl 滑动窗口确保过期条目可被清理
        Thread.sleep(70L);

        assertTrue(validator.validateAndStore("fresh-1", Duration.ofMinutes(5)),
                "过期条目被清理后应恢复接纳新 Nonce");
        assertTrue(validator.validateAndStore("fresh-2", Duration.ofMinutes(5)));
        assertFalse(validator.validateAndStore("fresh-3", Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("并发洪泛压测下严格约束接纳数量不超过 maxCapacity 上限")
    void testConcurrentFloodNeverExceedsMaxCapacity() throws Exception {
        int maxCapacity = 25;
        InMemoryGatewayNonceValidator validator = new InMemoryGatewayNonceValidator(maxCapacity);
        Duration ttl = Duration.ofMinutes(5);

        int threads = 8;
        int perThread = 20;
        ExecutorService executor = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger acceptedCount = new AtomicInteger(0);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        try {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < perThread; i++) {
                            if (validator.validateAndStore("flood-" + threadId + "-" + i, ttl)) {
                                acceptedCount.incrementAndGet();
                            }
                        }
                    } catch (Throwable ex) {
                        errors.add(ex);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertTrue(errors.isEmpty(), "并发洪泛不应抛出异常: " + errors);
        assertEquals(maxCapacity, acceptedCount.get(), "并发接纳唯一 Nonce 总数必须严格等于 maxCapacity");

        List<Boolean> extraResults = new ArrayList<>();
        extraResults.add(validator.validateAndStore("extra-after-flood", ttl));
        assertFalse(extraResults.get(0));
    }

    @Test
    @DisplayName("前置无锁快速失败：未过期 Nonce 重放被立即拦截且不影响其他正常 Nonce 写入")
    void testFastPathReplayRejection() {
        InMemoryGatewayNonceValidator validator = new InMemoryGatewayNonceValidator(10);
        Duration ttl = Duration.ofMinutes(5);

        assertTrue(validator.validateAndStore("replay-nonce-1", ttl));
        // 重复请求触发快速失败
        assertFalse(validator.validateAndStore("replay-nonce-1", ttl));
        assertFalse(validator.validateAndStore(" replay-nonce-1 ", ttl));

        // 其他全新 Nonce 仍可正常写入
        assertTrue(validator.validateAndStore("fresh-nonce-2", ttl));
    }
}
