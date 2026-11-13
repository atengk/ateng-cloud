package io.github.atengk.cloud.seata.lock;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import io.github.atengk.cloud.seata.exception.SeataExceptionTranslator;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.apache.seata.core.context.GlobalLockConfigHolder;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.model.GlobalLockConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 编程式高并发轻量全局锁执行模板
 * <p>
 * 提供函数式与 Lambda 友好的编程式全局锁执行门面，免去手动操作 {@link RootContext} 与 {@link GlobalLockConfigHolder}
 * 的繁琐生命周期，在 finally 块中提供确定性解绑与上下文恢复，杜绝锁标记泄露。
 * 配合 SELECT ... FOR UPDATE 仅向 TC 校验全局排他锁，免去 undo_log 开销。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SeataGlobalLockTemplate {

    private static final Logger log = LoggerFactory.getLogger(SeataGlobalLockTemplate.class);

    private final SeataProperties seataProperties;

    public SeataGlobalLockTemplate(SeataProperties seataProperties) {
        this.seataProperties = seataProperties != null ? seataProperties : new SeataProperties();
    }

    /**
     * 在全局锁上下文中执行有返回值的业务逻辑（采用默认生产锁重试配置）
     *
     * @param action 业务逻辑回调
     * @param <T>    返回值类型
     * @return 执行结果
     * @throws Throwable 业务抛出的异常
     */
    public <T> T execute(GlobalLockCallback<T> action) throws Throwable {
        return execute(defaultGlobalLockConfig(), action);
    }

    /**
     * 在全局锁上下文中执行有返回值的业务逻辑（指定自定义锁重试配置）
     *
     * @param config 自定义全局锁重试配置
     * @param action 业务逻辑回调
     * @param <T>    返回值类型
     * @return 执行结果
     * @throws Throwable 业务抛出的异常
     */
    public <T> T execute(GlobalLockConfig config, GlobalLockCallback<T> action) throws Throwable {
        Objects.requireNonNull(action, "GlobalLock action 必须非空");

        boolean alreadyRequireGlobalLock = RootContext.requireGlobalLock();
        if (!alreadyRequireGlobalLock) {
            RootContext.bindGlobalLockFlag();
            log.debug("编程式模板绑定 Seata GlobalLock 标记 (线程: [{}])", Thread.currentThread().getName());
        }

        GlobalLockConfig effectiveConfig = config != null ? config : defaultGlobalLockConfig();
        GlobalLockConfig previousConfig = GlobalLockConfigHolder.setAndReturnPrevious(effectiveConfig);

        try {
            return action.doInGlobalLock();
        } catch (Error error) {
            throw error;
        } catch (Throwable ex) {
            throw SeataExceptionTranslator.translate(ex);
        } finally {
            if (!alreadyRequireGlobalLock) {
                RootContext.unbindGlobalLockFlag();
                log.debug("编程式模板解绑 Seata GlobalLock 标记 (线程: [{}])", Thread.currentThread().getName());
            }
            if (previousConfig != null) {
                GlobalLockConfigHolder.setAndReturnPrevious(previousConfig);
            } else {
                GlobalLockConfigHolder.remove();
            }
        }
    }

    /**
     * 在全局锁上下文中执行有返回值的业务逻辑，并在遭遇锁竞争冲突时按默认配置自动退避重试
     *
     * @param action 业务逻辑回调
     * @param <T>    返回值类型
     * @return 执行结果
     * @throws Throwable 非锁冲突异常或重试耗尽后的 {@link BusinessException}
     */
    public <T> T executeWithRetry(GlobalLockCallback<T> action) throws Throwable {
        return executeWithRetry(defaultGlobalLockConfig(), action);
    }

    /**
     * 在全局锁上下文中执行有返回值的业务逻辑，并在遭遇锁竞争冲突时按指定配置执行温和退避重试
     *
     * @param config 自定义全局锁重试配置（为 null 时回退至默认配置）
     * @param action 业务逻辑回调
     * @param <T>    返回值类型
     * @return 执行结果
     * @throws Throwable 非锁冲突异常或重试耗尽后的 {@link BusinessException}
     */
    public <T> T executeWithRetry(GlobalLockConfig config, GlobalLockCallback<T> action) throws Throwable {
        Objects.requireNonNull(action, "GlobalLock action 必须非空");
        GlobalLockConfig effectiveConfig = config != null ? config : defaultGlobalLockConfig();
        int maxRetryTimes = Math.max(0, effectiveConfig.getLockRetryTimes());
        long retryIntervalMillis = Math.max(0L, effectiveConfig.getLockRetryInterval());

        for (int attempt = 0; ; attempt++) {
            try {
                return execute(effectiveConfig, action);
            } catch (Error error) {
                throw error;
            } catch (Throwable ex) {
                RuntimeException translated = (ex instanceof RuntimeException re)
                        ? re
                        : SeataExceptionTranslator.translate(ex);
                if (!isLockConflict(translated) || attempt >= maxRetryTimes) {
                    throw translated;
                }
                waitBeforeRetry(retryIntervalMillis, attempt + 1, maxRetryTimes, translated);
            }
        }
    }

    /**
     * 在全局锁上下文中执行无返回值的业务逻辑（采用默认生产锁重试配置）
     *
     * @param action 业务逻辑回调
     * @throws Throwable 业务抛出的异常
     */
    public void executeWithoutResult(Runnable action) throws Throwable {
        executeWithoutResult(defaultGlobalLockConfig(), action);
    }

    /**
     * 在全局锁上下文中执行无返回值的业务逻辑（指定自定义锁重试配置）
     *
     * @param config 自定义全局锁重试配置
     * @param action 业务逻辑回调
     * @throws Throwable 业务抛出的异常
     */
    public void executeWithoutResult(GlobalLockConfig config, Runnable action) throws Throwable {
        execute(config, toCallback(action));
    }

    /**
     * 在全局锁上下文中执行无返回值的业务逻辑，并在遭遇锁竞争冲突时按默认配置自动退避重试
     *
     * @param action 业务逻辑回调
     * @throws Throwable 非锁冲突异常或重试耗尽后的 {@link BusinessException}
     */
    public void executeWithoutResultWithRetry(Runnable action) throws Throwable {
        executeWithoutResultWithRetry(defaultGlobalLockConfig(), action);
    }

    /**
     * 在全局锁上下文中执行无返回值的业务逻辑，并在遭遇锁竞争冲突时按指定配置执行退避重试
     *
     * @param config 自定义全局锁重试配置（为 null 时回退至默认配置）
     * @param action 业务逻辑回调
     * @throws Throwable 非锁冲突异常或重试耗尽后的 {@link BusinessException}
     */
    public void executeWithoutResultWithRetry(GlobalLockConfig config, Runnable action) throws Throwable {
        executeWithRetry(config, toCallback(action));
    }

    private static GlobalLockCallback<Void> toCallback(Runnable action) {
        Objects.requireNonNull(action, "GlobalLock action 必须非空");
        return () -> {
            action.run();
            return null;
        };
    }

    private boolean isLockConflict(RuntimeException translated) {
        return translated instanceof BusinessException be
                && Objects.equals(SeataResultCode.SEATA_LOCK_CONFLICT.getCode(), be.getCode());
    }

    private void waitBeforeRetry(long retryIntervalMillis, int nextRetryAttempt, int maxRetryTimes, RuntimeException conflictException) {
        if (Thread.currentThread().isInterrupted()) {
            throw new BusinessException(
                    SeataResultCode.SEATA_LOCK_CONFLICT,
                    "全局锁冲突退避重试前检测到线程中断",
                    conflictException
            );
        }
        log.warn("检测到分布式全局锁冲突，准备第 {}/{} 次退避重试 (等待 {} ms): {}",
                nextRetryAttempt, maxRetryTimes, retryIntervalMillis, conflictException.getMessage());
        if (retryIntervalMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(retryIntervalMillis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    SeataResultCode.SEATA_LOCK_CONFLICT,
                    "全局锁冲突退避重试等待被中断",
                    conflictException
            );
        }
    }

    /**
     * 构建默认全局锁配置
     *
     * @return 默认 GlobalLockConfig
     */
    public GlobalLockConfig defaultGlobalLockConfig() {
        GlobalLockConfig config = new GlobalLockConfig();
        config.setLockRetryInterval((int) seataProperties.getLock().getRetryInterval().toMillis());
        config.setLockRetryTimes(seataProperties.getLock().getRetryTimes());
        return config;
    }
}
