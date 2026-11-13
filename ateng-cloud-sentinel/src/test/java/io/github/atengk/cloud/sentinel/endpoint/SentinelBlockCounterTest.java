package io.github.atengk.cloud.sentinel.endpoint;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SentinelBlockCounter 原子阻断计数器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class SentinelBlockCounterTest {

    private SentinelBlockCounter counter;

    @BeforeEach
    void setUp() {
        MDC.clear();
        counter = new SentinelBlockCounter();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("测试各类型阻断异常分别计数与总数统计")
    void shouldCountDifferentBlockExceptionsCorrectly() {
        counter.recordBlock(new FlowException("flow-limit"), "/api/order", "192.168.1.10");
        counter.recordBlock(new DegradeException("degrade-breaker"), "/api/pay", "192.168.1.11");
        counter.recordBlock(new ParamFlowException("/api/item", "item-101"), "/api/item", "192.168.1.12");
        counter.recordBlock(new SystemBlockException("system-limit", "cpu"), "/api/user", "192.168.1.13");
        counter.recordBlock(new AuthorityException("authority-limit"), "/api/admin", "192.168.1.14");
        counter.recordBlock(new CustomBlockException("custom"), "/api/other", "192.168.1.15");

        assertThat(counter.getBlockedTotal()).isEqualTo(6);
        assertThat(counter.getTotalBlocked()).isEqualTo(6);
        assertThat(counter.getFlowCount()).isEqualTo(1);
        assertThat(counter.getDegradeCount()).isEqualTo(1);
        assertThat(counter.getParamFlowCount()).isEqualTo(1);
        assertThat(counter.getSystemCount()).isEqualTo(1);
        assertThat(counter.getAuthorityCount()).isEqualTo(1);
        assertThat(counter.getOtherCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试最近一次阻断事件上下文快照")
    void shouldCaptureLastBlockEventSnapshot() {
        long before = System.currentTimeMillis();
        counter.recordBlock(new FlowException("flow-1"), "/api/first", "127.0.0.1");
        counter.recordBlock(new DegradeException("degrade-2"), "/api/second", "10.0.0.1");
        long after = System.currentTimeMillis();

        SentinelBlockCounter.BlockEventSnapshot snapshot = counter.getLastBlockEvent();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.resource()).isEqualTo("/api/second");
        assertThat(snapshot.ruleType()).isEqualTo("DEGRADE");
        assertThat(snapshot.clientIp()).isEqualTo("10.0.0.1");
        assertThat(snapshot.message()).contains("degrade-2");
        assertThat(snapshot.timestamp()).isBetween(before, after);
    }

    @Test
    @DisplayName("测试空异常与异常入参防御")
    void shouldHandleNullGracefully() {
        counter.recordBlock(null, "/api/test", "127.0.0.1");
        assertThat(counter.getTotalBlocked()).isEqualTo(0);
        assertThat(counter.getLastBlockEvent()).isNull();

        counter.recordBlock(new FlowException("test"), null, null);
        assertThat(counter.getTotalBlocked()).isEqualTo(1);
        assertThat(counter.getLastBlockEvent().resource()).isEqualTo("unknown");
        assertThat(counter.getLastBlockEvent().clientIp()).isNull();
    }

    @Test
    @DisplayName("测试多线程高并发原子递增无锁竞争与准确性")
    void shouldCountConcurrentlyWithoutRaceConditions() throws InterruptedException {
        int threads = 10;
        int iterationsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        boolean finished;
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < iterationsPerThread; j++) {
                            counter.recordBlock(new FlowException("concurrent"), "/api/stress", "192.168.1.1");
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            finished = latch.await(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(finished).isTrue();
        assertThat(counter.getTotalBlocked()).isEqualTo(threads * iterationsPerThread);
        assertThat(counter.getFlowCount()).isEqualTo(threads * iterationsPerThread);
    }

    @Test
    @DisplayName("测试计数器重置")
    void shouldResetSuccessfully() {
        counter.recordBlock(new FlowException("test"), "/api/test", "127.0.0.1");
        assertThat(counter.getTotalBlocked()).isEqualTo(1);

        counter.reset();

        assertThat(counter.getTotalBlocked()).isEqualTo(0);
        assertThat(counter.getFlowCount()).isEqualTo(0);
        assertThat(counter.getLastBlockEvent()).isNull();
    }

    @Test
    @DisplayName("测试快照扩展字段 traceId 与 origin 及 5 参数兼容构造器")
    void shouldCaptureTraceIdAndOriginAndSupportLegacyConstructor() {
        MDC.put("traceId", "trace-mdc-888");
        counter.recordBlock(new FlowException("flow-3"), "/api/orders", "10.0.0.2", null, "tenant:1001");

        SentinelBlockCounter.BlockEventSnapshot snapshot = counter.getLastBlockEvent();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.traceId()).isEqualTo("trace-mdc-888");
        assertThat(snapshot.origin()).isEqualTo("tenant:1001");

        // 显式传入 traceId 优先于 MDC
        counter.recordBlock(new DegradeException("degrade-3"), "/api/pay", "10.0.0.3", "trace-explicit-999", "order-service");
        SentinelBlockCounter.BlockEventSnapshot explicitSnapshot = counter.getLastBlockEvent();
        assertThat(explicitSnapshot.traceId()).isEqualTo("trace-explicit-999");
        assertThat(explicitSnapshot.origin()).isEqualTo("order-service");

        // 兼容旧 5 参数构造器
        SentinelBlockCounter.BlockEventSnapshot legacy = new SentinelBlockCounter.BlockEventSnapshot(
                1000L, "/api/legacy", "FLOW", "127.0.0.1", "legacy-msg");
        assertThat(legacy.traceId()).isEqualTo("trace-mdc-888");
        assertThat(legacy.origin()).isNull();
    }

    private static class CustomBlockException extends BlockException {
        public CustomBlockException(String rule) {
            super(rule);
        }
    }
}
