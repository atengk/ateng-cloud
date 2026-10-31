package io.github.atengk.cloud.seata.fallback;

/**
 * Seata 分布式事务断言工具类
 * <p>
 * 提供对分布式事务上下文的语义化断言方法，
 * 支持在熔断降级边界等场景快速校验事务状态并实施快速失败防卫。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public final class SeataAssert {

    private SeataAssert() {
    }

    /**
     * 判断当前执行线程是否处于活跃的 Seata 分布式全局事务上下文中
     *
     * @return true 表示存在活跃的全局事务 XID，false 表示当前为无事务普通调用
     * @see SeataFallbackDefense#hasActiveTransaction()
     */
    public static boolean hasActiveTransaction() {
        return SeataFallbackDefense.hasActiveTransaction();
    }

    /**
     * 断言当前不存在活跃的全局分布式事务，若存在则立即阻断并抛出 BusinessException
     */
    public static void assertNoActiveTransaction() {
        failOnActiveTransaction();
    }

    /**
     * 断言当前不存在活跃的全局分布式事务（附带业务动作说明）
     *
     * @param actionDesc 下游调用业务动作描述
     */
    public static void assertNoActiveTransaction(String actionDesc) {
        failOnActiveTransaction(actionDesc);
    }

    /**
     * 断言当前不存在活跃的全局分布式事务（保留原始异常因果）
     *
     * @param cause 原始异常
     */
    public static void assertNoActiveTransaction(Throwable cause) {
        failOnActiveTransaction(cause);
    }

    /**
     * 断言当前不存在活跃的全局分布式事务（附带业务动作说明并保留原始因果）
     *
     * @param actionDesc 下游调用业务动作描述
     * @param cause      原始异常
     */
    public static void assertNoActiveTransaction(String actionDesc, Throwable cause) {
        failOnActiveTransaction(actionDesc, cause);
    }

    /**
     * 断言当前不存在活跃的全局分布式事务，若存在则立即阻断并抛出 BusinessException
     *
     * @see SeataFallbackDefense#failOnActiveTransaction()
     */
    public static void failOnActiveTransaction() {
        SeataFallbackDefense.failOnActiveTransaction();
    }

    /**
     * 断言当前不存在活跃的全局分布式事务（附带业务动作说明）
     *
     * @param actionDesc 下游调用业务动作描述
     * @see SeataFallbackDefense#failOnActiveTransaction(String)
     */
    public static void failOnActiveTransaction(String actionDesc) {
        SeataFallbackDefense.failOnActiveTransaction(actionDesc);
    }

    /**
     * 断言当前不存在活跃的全局分布式事务（保留原始异常因果）
     *
     * @param cause 原始异常
     * @see SeataFallbackDefense#failOnActiveTransaction(Throwable)
     */
    public static void failOnActiveTransaction(Throwable cause) {
        SeataFallbackDefense.failOnActiveTransaction(cause);
    }

    /**
     * 断言当前不存在活跃的全局分布式事务（附带业务动作说明并保留原始因果）
     *
     * @param actionDesc 下游调用业务动作描述
     * @param cause      原始异常
     * @see SeataFallbackDefense#failOnActiveTransaction(String, Throwable)
     */
    public static void failOnActiveTransaction(String actionDesc, Throwable cause) {
        SeataFallbackDefense.failOnActiveTransaction(actionDesc, cause);
    }
}
