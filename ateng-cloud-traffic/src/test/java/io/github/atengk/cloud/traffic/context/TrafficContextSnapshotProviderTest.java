package io.github.atengk.cloud.traffic.context;

import io.github.atengk.boot.core.context.AsyncContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流量上下文异步快照提供者单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class TrafficContextSnapshotProviderTest {

    private final TrafficContextSnapshotProvider provider = new TrafficContextSnapshotProvider();

    @BeforeEach
    @AfterEach
    void cleanUp() {
        TrafficContextHolder.clear();
    }

    @Test
    @DisplayName("测试手动快照捕获与应用还原")
    void testCaptureApplyAndRestore() {
        TrafficContextHolder.setTag("base-tag");
        Object snapshot = provider.capture();

        assertThat(snapshot).isInstanceOf(TrafficContext.class);
        assertThat(((TrafficContext) snapshot).getTag()).isEqualTo("base-tag");

        // 在新线程或上下文中模拟 apply 与 restore
        TrafficContextHolder.setTag("other-tag");
        assertThat(TrafficContextHolder.getTag()).isEqualTo("other-tag");

        provider.apply(snapshot);
        assertThat(TrafficContextHolder.getTag()).isEqualTo("base-tag");

        provider.restore(null);
        assertThat(TrafficContextHolder.getTag()).isNull();
    }

    @Test
    @DisplayName("测试与 AsyncContext.wrap 机制集成跨线程透传")
    void testIntegrationWithAsyncContextWrap() throws ExecutionException, InterruptedException {
        TrafficContextHolder.setTag("async-wrapped-tag");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<String> future = new CompletableFuture<>();

            // 使用 AsyncContext.wrap 包装异步 Runnable
            Runnable wrappedRunnable = AsyncContext.wrap(() -> {
                future.complete(TrafficContextHolder.getTag());
            });

            // 模拟主线程清理后异步提交
            TrafficContextHolder.clear();
            executor.submit(wrappedRunnable);

            String tagInWorker = future.get();
            assertThat(tagInWorker).isEqualTo("async-wrapped-tag");
        } finally {
            executor.shutdownNow();
        }
    }
}
