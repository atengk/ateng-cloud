package io.github.atengk.cloud.seata.tcc;

import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * TCC 防卫日志分级生命周期批量清理调度器
 * <p>
 * 依托 Spring {@link SmartLifecycle} 规范纳管生命周期，落实受管线程调度。
 * 执行分级生命周期保留策略（Hierarchical TCC Retention）：
 * <ul>
 *     <li>终态业务记录（{@link TccFenceStatus#COMMITTED} 与 {@link TccFenceStatus#ROLLBACKED}）：
 *     达到配置保留时效（默认 3 天）时执行分批物理删除；</li>
 *     <li>悬挂防卫标记（{@link TccFenceStatus#SUSPENDED}）：
 *     作为安全护盾实行 3 倍超长保留窗口（默认 9 天），杜绝极端网络抖动下迟到 Try 绕过屏障的资损隐患。</li>
 * </ul>
 * 每次物理删除严格限制批次阈值，防御高并发锁表与慢查询。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class TccFenceLogCleanScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TccFenceLogCleanScheduler.class);

    private static final int MAX_BATCH_ROUNDS_PER_CLEAN = 100;

    private final TccFenceRepository repository;
    private final SeataProperties properties;
    private final ScheduledExecutorService scheduledExecutor;
    private final boolean internalExecutor;

    private volatile boolean running = false;
    private ScheduledFuture<?> scheduledFuture;

    public TccFenceLogCleanScheduler(TccFenceRepository repository, SeataProperties properties) {
        this(repository, properties, null);
    }

    public TccFenceLogCleanScheduler(TccFenceRepository repository, SeataProperties properties, ScheduledExecutorService customExecutor) {
        this.repository = Objects.requireNonNull(repository, "repository 必须非空");
        this.properties = Objects.requireNonNull(properties, "properties 必须非空");
        if (customExecutor != null) {
            this.scheduledExecutor = customExecutor;
            this.internalExecutor = false;
        } else {
            this.scheduledExecutor = new ScheduledThreadPoolExecutor(1, new CustomizableThreadFactory("seata-tcc-clean-"));
            this.internalExecutor = true;
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        SeataProperties.TccProperties tcc = properties != null ? properties.getTcc() : null;
        if (tcc == null || !tcc.isCleanEnabled()) {
            log.info("TCC 防卫日志定时清理已被配置禁用 (clean-enabled=false)，跳过启动");
            running = true;
            return;
        }
        Duration cleanPeriod = tcc.getCleanPeriod();
        if (cleanPeriod == null || cleanPeriod.isNegative() || cleanPeriod.isZero()) {
            cleanPeriod = Duration.ofDays(1);
        }
        long periodSeconds = Math.max(1, cleanPeriod.toSeconds());
        log.info("启动 TCC 防卫日志分级生命周期定时清理调度器, 执行周期: {} 秒, 终态保留期: {}, 悬挂倍数: {}x",
                periodSeconds, tcc.getLogTtl(), tcc.getSuspendedRetentionMultiplier());

        scheduledFuture = scheduledExecutor.scheduleWithFixedDelay(
                this::safeClean,
                periodSeconds,
                periodSeconds,
                TimeUnit.SECONDS
        );
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        log.info("停止 TCC 防卫日志定时清理调度器");
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
        if (internalExecutor) {
            scheduledExecutor.shutdown();
            try {
                if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduledExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    /**
     * 安全执行清理（拦截所有异常，防止定时调度线程被异常终止中断）
     */
    public void safeClean() {
        try {
            clean();
        } catch (Throwable t) {
            log.error("TCC 防卫日志定时清理任务执行异常: {}", t.getMessage(), t);
        }
    }

    /**
     * 触发一次分级生命周期批量物理清理（可供定时任务、管理端点或测试手动触发）
     *
     * @return 清理结果摘要
     */
    public TccFenceCleanResult clean() {
        LocalDateTime now = LocalDateTime.now();
        SeataProperties.TccProperties tcc = properties != null ? properties.getTcc() : null;
        Duration logTtl = tcc != null ? tcc.getLogTtl() : null;
        if (logTtl == null || logTtl.isNegative() || logTtl.isZero()) {
            logTtl = Duration.ofDays(3);
        }
        int multiplier = Math.max(1, tcc != null ? tcc.getSuspendedRetentionMultiplier() : 3);
        int batchSize = Math.max(1, tcc != null ? tcc.getCleanBatchSize() : 500);

        // 1. 终态记录（COMMITTED / ROLLBACKED）过期阈值与批量清理
        LocalDateTime terminalThreshold = now.minus(logTtl);
        List<TccFenceStatus> terminalStatuses = List.of(TccFenceStatus.COMMITTED, TccFenceStatus.ROLLBACKED);
        int terminalDeleted = cleanBatchLoop(terminalThreshold, terminalStatuses, batchSize);

        // 2. 悬挂记录（SUSPENDED）超长保留阈值与批量清理
        Duration suspendedTtl = logTtl.multipliedBy(multiplier);
        LocalDateTime suspendedThreshold = now.minus(suspendedTtl);
        List<TccFenceStatus> suspendedStatuses = List.of(TccFenceStatus.SUSPENDED);
        int suspendedDeleted = cleanBatchLoop(suspendedThreshold, suspendedStatuses, batchSize);

        TccFenceCleanResult result = new TccFenceCleanResult(terminalDeleted, suspendedDeleted);
        if (result.getTotalDeleted() > 0) {
            log.info("TCC 防卫日志分级清理完成: 终态记录删除 {} 条 (保留期 {}), 悬挂记录删除 {} 条 (保留期 {}), 共物理删除 {} 条",
                    terminalDeleted, logTtl, suspendedDeleted, suspendedTtl, result.getTotalDeleted());
        } else {
            log.debug("TCC 防卫日志分级清理执行完毕，无过期历史记录");
        }
        return result;
    }

    private int cleanBatchLoop(LocalDateTime threshold, List<TccFenceStatus> statuses, int batchSize) {
        int total = 0;
        for (int i = 0; i < MAX_BATCH_ROUNDS_PER_CLEAN; i++) {
            int deleted = repository.deleteExpiredRecords(threshold, statuses, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }

    public TccFenceRepository getRepository() {
        return repository;
    }

    public SeataProperties getProperties() {
        return properties;
    }
}
