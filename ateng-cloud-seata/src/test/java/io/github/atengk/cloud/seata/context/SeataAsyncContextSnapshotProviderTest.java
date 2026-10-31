package io.github.atengk.cloud.seata.context;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.cloud.seata.constant.SeataConstants;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seata 异步任务快照提供者单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class SeataAsyncContextSnapshotProviderTest {

    private final SeataAsyncContextSnapshotProvider provider = new SeataAsyncContextSnapshotProvider();

    @BeforeEach
    @AfterEach
    void cleanContext() {
        RootContext.unbind();
        MDC.remove(SeataConstants.MDC_KEY_XID);
    }

    @Test
    @DisplayName("测试手动捕获、应用与还原 XID 快照，全生命周期联动 MDC")
    void testCaptureApplyAndRestore() {
        String testXid = "192.168.1.50:8091:77777777";
        RootContext.bind(testXid);

        Object snapshot = provider.capture();
        assertThat(snapshot).isEqualTo(testXid);

        // 模拟子线程初始为空
        RootContext.unbind();
        MDC.remove(SeataConstants.MDC_KEY_XID);
        assertThat(RootContext.getXID()).isNull();
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isNull();

        // 子线程执行前应用快照
        provider.apply(snapshot);
        assertThat(RootContext.getXID()).isEqualTo(testXid);
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isEqualTo(testXid);

        // 子线程任务完成后还原
        provider.restore(null);
        assertThat(RootContext.getXID()).isNull();
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isNull();
    }

    @Test
    @DisplayName("测试与 AsyncContext.wrap 跨线程透传基础设施无缝集成，MDC 与 RootContext 同步穿透")
    void testIntegrationWithAsyncContextWrap() throws ExecutionException, InterruptedException {
        String testXid = "192.168.1.50:8091:99999999";
        RootContext.bind(testXid);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<String[]> future = new CompletableFuture<>();

            // 使用 AsyncContext.wrap 捕获当前所有上下文快照
            Runnable wrappedTask = AsyncContext.wrap(() -> {
                future.complete(new String[]{RootContext.getXID(), MDC.get(SeataConstants.MDC_KEY_XID)});
            });

            // 模拟主线程清理后，异步提交任务
            RootContext.unbind();
            MDC.remove(SeataConstants.MDC_KEY_XID);
            executor.submit(wrappedTask);

            String[] inWorker = future.get();
            assertThat(inWorker[0]).isEqualTo(testXid);
            assertThat(inWorker[1]).isEqualTo(testXid);
        } finally {
            executor.shutdownNow();
        }
    }
}
