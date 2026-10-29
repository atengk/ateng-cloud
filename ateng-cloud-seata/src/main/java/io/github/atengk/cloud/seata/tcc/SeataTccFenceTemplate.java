package io.github.atengk.cloud.seata.tcc;

import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 编程式 TCC Fence 业务防卫执行模板
 * <p>
 * 为业务开发提供函数式与 Lambda 友好的 TCC Fence 执行门面，
 * 封装 {@link TccFenceBarrier} 的一阶段 Try、二阶段 Confirm 与 Cancel 调用，
 * 全自动防御“空回滚 (Empty Rollback)”、“业务悬挂 (Hanging)”与“重复提交 (Idempotent)”。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SeataTccFenceTemplate {

    private static final Logger log = LoggerFactory.getLogger(SeataTccFenceTemplate.class);

    private final TccFenceBarrier barrier;

    public SeataTccFenceTemplate(TccFenceBarrier barrier) {
        this.barrier = Objects.requireNonNull(barrier, "TccFenceBarrier 必须非空");
    }

    /**
     * 在 TCC 防卫屏障保护下执行一阶段 Try 操作
     *
     * @param xid        全局事务 ID
     * @param branchId   分支事务 ID
     * @param actionName TCC 动作名称
     * @param action     业务 Try 逻辑
     * @param <T>        返回值类型
     * @return 业务 Try 执行结果
     * @throws Throwable 业务异常或防卫异常
     */
    public <T> T executePrepare(String xid, Long branchId, String actionName, TccFenceCallback<T> action) throws Throwable {
        return barrier.prepareFence(xid, branchId, actionName, action);
    }

    /**
     * 在 TCC 防卫屏障保护下执行一阶段 Try 操作（基于 BusinessActionContext）
     *
     * @param actionContext 业务执行上下文
     * @param actionName    TCC 动作名称
     * @param action        业务 Try 逻辑
     * @param <T>           返回值类型
     * @return 业务 Try 执行结果
     * @throws Throwable 业务异常或防卫异常
     */
    public <T> T executePrepare(BusinessActionContext actionContext, String actionName, TccFenceCallback<T> action) throws Throwable {
        return barrier.prepareFence(actionContext, actionName, action);
    }

    /**
     * 在 TCC 防卫屏障保护下执行二阶段 Confirm 提交操作
     *
     * @param xid      全局事务 ID
     * @param branchId 分支事务 ID
     * @param action   业务 Confirm 逻辑
     * @return 提交成功返回 true
     * @throws Throwable 业务异常或防卫异常
     */
    public boolean executeCommit(String xid, Long branchId, TccFenceCallback<Boolean> action) throws Throwable {
        return barrier.commitFence(xid, branchId, action);
    }

    /**
     * 在 TCC 防卫屏障保护下执行二阶段 Confirm 提交操作（基于 BusinessActionContext）
     *
     * @param actionContext 业务执行上下文
     * @param action        业务 Confirm 逻辑
     * @return 提交成功返回 true
     * @throws Throwable 业务异常或防卫异常
     */
    public boolean executeCommit(BusinessActionContext actionContext, TccFenceCallback<Boolean> action) throws Throwable {
        return barrier.commitFence(actionContext, action);
    }

    /**
     * 在 TCC 防卫屏障保护下执行二阶段 Cancel 回滚操作
     *
     * @param xid        全局事务 ID
     * @param branchId   分支事务 ID
     * @param actionName TCC 动作名称
     * @param action     业务 Cancel 补偿逻辑
     * @return 回滚成功返回 true
     * @throws Throwable 业务异常或防卫异常
     */
    public boolean executeRollback(String xid, Long branchId, String actionName, TccFenceCallback<Boolean> action) throws Throwable {
        return barrier.rollbackFence(xid, branchId, actionName, action);
    }

    /**
     * 在 TCC 防卫屏障保护下执行二阶段 Cancel 回滚操作（基于 BusinessActionContext）
     *
     * @param actionContext 业务执行上下文
     * @param action        业务 Cancel 补偿逻辑
     * @return 回滚成功返回 true
     * @throws Throwable 业务异常或防卫异常
     */
    public boolean executeRollback(BusinessActionContext actionContext, TccFenceCallback<Boolean> action) throws Throwable {
        return barrier.rollbackFence(actionContext, action);
    }

    public TccFenceBarrier getBarrier() {
        return barrier;
    }
}
