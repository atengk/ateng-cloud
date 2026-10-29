package io.github.atengk.cloud.traffic.context;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流量上下文持有者与 MDC 双写双读单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class TrafficContextHolderTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        TrafficContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试基础标签设置与读取")
    void testBasicSetAndGet() {
        TrafficContextHolder.setTag("canary-v1");

        assertThat(TrafficContextHolder.getTag()).isEqualTo("canary-v1");
        assertThat(TrafficContextHolder.getContext()).isNotNull();
        assertThat(TrafficContextHolder.getContext().getTag()).isEqualTo("canary-v1");
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isEqualTo("canary-v1");
    }

    @Test
    @DisplayName("测试设置完整上下文与元数据")
    void testFullContext() {
        TrafficContext ctx = new TrafficContext("gray-prod", "vip-lane", "rule-1001", Map.of("tenantId", "tenant-001"));
        TrafficContextHolder.setContext(ctx);

        TrafficContext current = TrafficContextHolder.getContext();
        assertThat(current).isNotNull();
        assertThat(current.getTag()).isEqualTo("gray-prod");
        assertThat(current.getLaneName()).isEqualTo("vip-lane");
        assertThat(current.getMatchedRule()).isEqualTo("rule-1001");
        assertThat(current.getMetadata()).containsEntry("tenantId", "tenant-001");
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isEqualTo("gray-prod");
    }

    @Test
    @DisplayName("测试上下文清理与 MDC 同步清除")
    void testClear() {
        TrafficContextHolder.setTag("temp-tag");
        assertThat(TrafficContextHolder.getTag()).isEqualTo("temp-tag");
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isEqualTo("temp-tag");

        TrafficContextHolder.clear();
        assertThat(TrafficContextHolder.getTag()).isNull();
        assertThat(TrafficContextHolder.getContext()).isNull();
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isNull();
    }

    @Test
    @DisplayName("测试空值设置触发自动清理")
    void testNullOrEmptySet() {
        TrafficContextHolder.setTag("valid-tag");
        assertThat(TrafficContextHolder.getTag()).isEqualTo("valid-tag");

        TrafficContextHolder.setTag("   ");
        assertThat(TrafficContextHolder.getTag()).isNull();
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isNull();

        TrafficContextHolder.setTag("valid-tag-2");
        TrafficContextHolder.setContext(null);
        assertThat(TrafficContextHolder.getTag()).isNull();
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isNull();
    }

    @Test
    @DisplayName("测试 MDC 兜底对齐逻辑")
    void testMdcFallbackAlignment() {
        // 未通过 TrafficContextHolder 设置，但外部在 MDC 中写入了标签
        MDC.put(TrafficConstants.MDC_TRAFFIC_TAG, "mdc-only-tag");

        // 读取时触发自动对齐
        assertThat(TrafficContextHolder.getTag()).isEqualTo("mdc-only-tag");
        assertThat(TrafficContextHolder.getContext()).isNotNull();
        assertThat(TrafficContextHolder.getContext().getTag()).isEqualTo("mdc-only-tag");
    }

    @Test
    @DisplayName("测试 runWithTag 临时作用域与自动恢复")
    void testRunWithTag() {
        TrafficContextHolder.setTag("original-tag");

        TrafficContextHolder.runWithTag("scoped-tag", () -> {
            assertThat(TrafficContextHolder.getTag()).isEqualTo("scoped-tag");
            assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isEqualTo("scoped-tag");
        });

        // 作用域结束后自动还原为原始值
        assertThat(TrafficContextHolder.getTag()).isEqualTo("original-tag");
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isEqualTo("original-tag");

        // 带返回值的 Supplier 作用域
        String result = TrafficContextHolder.runWithTag("scoped-supplier", () -> {
            assertThat(TrafficContextHolder.getTag()).isEqualTo("scoped-supplier");
            return "SUCCESS";
        });

        assertThat(result).isEqualTo("SUCCESS");
        assertThat(TrafficContextHolder.getTag()).isEqualTo("original-tag");
    }

    @Test
    @DisplayName("测试多线程 TransmittableThreadLocal 跨线程传递")
    void testTransmittableThreadLocalInheritance() throws ExecutionException, InterruptedException {
        TrafficContextHolder.setTag("cross-thread-tag");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                // 子线程应当能读取到父线程设置的流量标签
                return TrafficContextHolder.getTag();
            }, executor);

            String tagInChildThread = future.get();
            assertThat(tagInChildThread).isEqualTo("cross-thread-tag");
        } finally {
            executor.shutdownNow();
        }
    }
}
