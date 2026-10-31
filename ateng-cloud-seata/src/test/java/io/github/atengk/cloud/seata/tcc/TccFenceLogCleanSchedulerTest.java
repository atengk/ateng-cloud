package io.github.atengk.cloud.seata.tcc;

import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCC 防卫日志分级生命周期定时清理调度器单测
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("TccFenceLogCleanScheduler 分级生命周期调度清理单测")
class TccFenceLogCleanSchedulerTest {

    private InMemoryTccFenceRepository repository;
    private SeataProperties properties;
    private TccFenceLogCleanScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTccFenceRepository();
        properties = new SeataProperties();
        properties.getTcc().setLogTtl(Duration.ofDays(3));
        properties.getTcc().setSuspendedRetentionMultiplier(3); // 3 * 3 = 9 天
        properties.getTcc().setCleanBatchSize(10);
        properties.getTcc().setCleanPeriod(Duration.ofDays(1));

        scheduler = new TccFenceLogCleanScheduler(repository, properties);
    }

    @AfterEach
    void tearDown() {
        if (scheduler.isRunning()) {
            scheduler.stop();
        }
    }

    @Test
    @DisplayName("分级生命周期保留策略验证：终态 3 天清理，悬挂态 9 天超长保留，进行中永不清理")
    void shouldPerformHierarchicalRetentionCleanup() {
        LocalDateTime now = LocalDateTime.now();

        // 终态记录（4 天前，已过 3 天 TTL）：应被清理
        repository.insertRecord(new TccFenceRecord("xid-term-1", 1L, "action", TccFenceStatus.COMMITTED, now.minusDays(4), now.minusDays(4)));
        repository.insertRecord(new TccFenceRecord("xid-term-2", 2L, "action", TccFenceStatus.ROLLBACKED, now.minusDays(4), now.minusDays(4)));

        // 悬挂记录（4 天前，未达 9 天悬挂 TTL）：必须保留！
        repository.insertRecord(new TccFenceRecord("xid-susp-1", 3L, "action", TccFenceStatus.SUSPENDED, now.minusDays(4), now.minusDays(4)));

        // 悬挂记录（10 天前，已过 9 天悬挂 TTL）：应被清理
        repository.insertRecord(new TccFenceRecord("xid-susp-2", 4L, "action", TccFenceStatus.SUSPENDED, now.minusDays(10), now.minusDays(10)));

        // 进行中记录（10 天前，TRIED 状态）：绝不清理！
        repository.insertRecord(new TccFenceRecord("xid-tried-1", 5L, "action", TccFenceStatus.TRIED, now.minusDays(10), now.minusDays(10)));

        // 近期终态记录（1 天前，未过 3 天 TTL）：必须保留
        repository.insertRecord(new TccFenceRecord("xid-term-recent", 6L, "action", TccFenceStatus.COMMITTED, now.minusDays(1), now.minusDays(1)));

        // 执行清理
        TccFenceCleanResult result = scheduler.clean();

        // 断言清理结果统计
        assertThat(result.getTerminalDeleted()).isEqualTo(2);
        assertThat(result.getSuspendedDeleted()).isEqualTo(1);
        assertThat(result.getTotalDeleted()).isEqualTo(3);

        // 断言具体记录留存状态
        assertThat(repository.findByXidAndBranchId("xid-term-1", 1L)).isEmpty();
        assertThat(repository.findByXidAndBranchId("xid-term-2", 2L)).isEmpty();
        assertThat(repository.findByXidAndBranchId("xid-susp-2", 4L)).isEmpty();

        // 验证受保护记录依然健在
        assertThat(repository.findByXidAndBranchId("xid-susp-1", 3L)).isPresent();
        assertThat(repository.findByXidAndBranchId("xid-tried-1", 5L)).isPresent();
        assertThat(repository.findByXidAndBranchId("xid-term-recent", 6L)).isPresent();
    }

    @Test
    @DisplayName("多轮批次清理验证：数据量大于单批阈值时循环分批清理")
    void shouldBatchCleanAcrossMultipleRounds() {
        LocalDateTime oldTime = LocalDateTime.now().minusDays(5);

        // 插入 25 条过期记录，单批大小为 10
        for (int i = 1; i <= 25; i++) {
            repository.insertRecord(new TccFenceRecord("xid-batch-" + i, (long) i, "action", TccFenceStatus.COMMITTED, oldTime, oldTime));
        }

        TccFenceCleanResult result = scheduler.clean();

        assertThat(result.getTerminalDeleted()).isEqualTo(25);
        assertThat(result.getTotalDeleted()).isEqualTo(25);

        for (int i = 1; i <= 25; i++) {
            assertThat(repository.findByXidAndBranchId("xid-batch-" + i, (long) i)).isEmpty();
        }
    }

    @Test
    @DisplayName("配置禁用 clean-enabled 时跳过定时启动但保留手动触发")
    void shouldRespectCleanDisabledConfiguration() {
        properties.getTcc().setCleanEnabled(false);

        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();

        // 手动调用依然可用
        TccFenceCleanResult result = scheduler.clean();
        assertThat(result.getTotalDeleted()).isEqualTo(0);

        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    @DisplayName("无过期历史记录时清理返回 0")
    void shouldHandleZeroExpiredRecordsGracefully() {
        TccFenceCleanResult result = scheduler.clean();
        assertThat(result.getTotalDeleted()).isEqualTo(0);
        assertThat(result.getTerminalDeleted()).isEqualTo(0);
        assertThat(result.getSuspendedDeleted()).isEqualTo(0);
    }

    @Test
    @DisplayName("safeClean 遇到异常时静默捕获并记录日志，防止终止调度器")
    void shouldHandleExceptionsInSafeCleanGracefully() {
        TccFenceRepository faultyRepo = new InMemoryTccFenceRepository() {
            @Override
            public int deleteExpiredRecords(LocalDateTime beforeTime, java.util.List<TccFenceStatus> statuses, int limit) {
                throw new RuntimeException("Simulated DB connection error");
            }
        };

        TccFenceLogCleanScheduler faultyScheduler = new TccFenceLogCleanScheduler(faultyRepo, properties);
        // 调用 safeClean 不应抛出异常
        faultyScheduler.safeClean();
    }

    @Test
    @DisplayName("Spring 生命周期方法契约校验")
    void shouldVerifyLifecycleContract() {
        assertThat(scheduler.isAutoStartup()).isTrue();
        assertThat(scheduler.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
        assertThat(scheduler.getRepository()).isSameAs(repository);
        assertThat(scheduler.getProperties()).isSameAs(properties);

        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();

        // 重复调用 start 幂等
        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();

        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();

        // 重复调用 stop 幂等
        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
    }
}
