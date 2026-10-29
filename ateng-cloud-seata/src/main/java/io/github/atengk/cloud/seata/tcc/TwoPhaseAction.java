package io.github.atengk.cloud.seata.tcc;

import org.apache.seata.rm.tcc.api.BusinessActionContext;

/**
 * 标准 TCC 两阶段分布式事务业务动作规范接口
 * <p>
 * 遵循 Seata TCC 两阶段执行规范，标准化定义二阶段提交（Commit）与回滚（Rollback）接口签名。
 * 业务服务接口继承或实现本规范，配合 {@code @LocalTCC} 与 Try 方法标注 {@code @TwoPhaseBusinessAction}
 * 实现与 Seata TM/RM 生命周期的一致闭环。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public interface TwoPhaseAction {

    /**
     * 二阶段提交业务确认方法
     *
     * @param actionContext 业务执行上下文（携带 XID、BranchId 与入参上下文）
     * @return 提交成功返回 true
     */
    boolean commit(BusinessActionContext actionContext);

    /**
     * 二阶段回滚业务补偿方法
     *
     * @param actionContext 业务执行上下文（携带 XID、BranchId 与入参上下文）
     * @return 回滚成功返回 true
     */
    boolean rollback(BusinessActionContext actionContext);
}
