package io.github.atengk.cloud.seata.lock;

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
        Objects.requireNonNull(action, "GlobalLock action 必须非空");
        execute(config, () -> {
            action.run();
            return null;
        });
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
