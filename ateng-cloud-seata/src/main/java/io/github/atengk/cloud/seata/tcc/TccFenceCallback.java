package io.github.atengk.cloud.seata.tcc;

/**
 * TCC Fence 业务操作函数式回调接口
 *
 * @param <T> 返回值类型
 * @author Ateng
 * @since 2026-10-29
 */
@FunctionalInterface
public interface TccFenceCallback<T> {

    /**
     * 在 TCC Fence 业务防卫屏障保护下执行业务逻辑
     *
     * @return 业务执行结果
     * @throws Throwable 业务抛出的异常
     */
    T execute() throws Throwable;
}
