package io.github.atengk.cloud.seata.lock;

/**
 * 全局锁业务回调接口
 * <p>
 * 供 {@link SeataGlobalLockTemplate} 编程式执行全局锁逻辑，允许方法内部自由抛出任何受检或运行时异常。
 * </p>
 *
 * @param <T> 返回值类型
 * @author Ateng
 * @since 2026-10-29
 */
@FunctionalInterface
public interface GlobalLockCallback<T> {

    /**
     * 在全局锁上下文中执行业务逻辑
     *
     * @return 执行结果
     * @throws Throwable 业务逻辑抛出的任何异常
     */
    T doInGlobalLock() throws Throwable;
}
