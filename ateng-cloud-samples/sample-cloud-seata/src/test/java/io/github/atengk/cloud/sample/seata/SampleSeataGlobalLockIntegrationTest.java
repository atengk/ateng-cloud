package io.github.atengk.cloud.sample.seata;

import io.github.atengk.cloud.sample.seata.service.StorageService;
import io.github.atengk.cloud.seata.lock.SeataGlobalLockTemplate;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高并发 @GlobalLock 轻量全局排他锁端到端集成测试
 * <p>
 * 验证声明式 @GlobalLock 切面与编程式 SeataGlobalLockTemplate 在真实 Spring 容器中的执行行为：
 * 配合 SELECT ... FOR UPDATE 仅校验 TC 全局排他锁，免去 undo_log 开销。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest(classes = SampleSeataApplication.class)
@DisplayName("@GlobalLock 轻量全局排他锁端到端集成测试")
class SampleSeataGlobalLockIntegrationTest {

    @Autowired
    private StorageService storageService;

    @Autowired
    private SeataGlobalLockTemplate globalLockTemplate;

    private static final String COMMODITY_CODE = "product_lock_001";

    @BeforeEach
    void setUp() {
        storageService.initStock(COMMODITY_CODE, 50);
    }

    @AfterEach
    void tearDown() {
        if (RootContext.requireGlobalLock()) {
            RootContext.unbindGlobalLockFlag();
        }
    }

    @Test
    @DisplayName("测试声明式 @GlobalLock 切面执行与锁标记绑定/清理")
    void shouldExecuteDeclarativeGlobalLock() {
        assertThat(RootContext.requireGlobalLock()).isFalse();

        boolean hasStock = storageService.checkAndLockStockDeclarative(COMMODITY_CODE, 10);
        assertThat(hasStock).isTrue();

        // 关键断言：方法执行完毕后，GlobalLock 标记必须在 finally 中彻底清理
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @Test
    @DisplayName("测试编程式 SeataGlobalLockTemplate 执行与锁标记绑定/清理")
    void shouldExecuteProgrammaticGlobalLock() throws Throwable {
        assertThat(RootContext.requireGlobalLock()).isFalse();

        boolean hasStock = storageService.checkAndLockStockProgrammatic(COMMODITY_CODE, 30);
        assertThat(hasStock).isTrue();

        // 关键断言：模板执行完毕后，标记必须彻底清理
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @Test
    @DisplayName("测试多线程并发执行 @GlobalLock 互不污染且标记确定性释放")
    void shouldExecuteConcurrentlyWithoutLeak() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    boolean result = storageService.checkAndLockStockDeclarative(COMMODITY_CODE, 5);
                    if (result && !RootContext.requireGlobalLock()) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }
}
