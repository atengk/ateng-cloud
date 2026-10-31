package io.github.atengk.cloud.seata.tcc;

import java.util.Optional;

/**
 * TCC Fence 本地事务分支日志仓储接口
 * <p>
 * 提供针对本地防卫表 {@code tcc_fence_log} 的持久化与状态原子转移操作，
 * 支撑防卫屏障在两阶段执行中对空回滚、业务悬挂与幂等的原子裁决。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public interface TccFenceRepository {

    /**
     * 根据全局事务 ID 与分支事务 ID 查询防卫日志
     *
     * @param xid      全局事务 ID
     * @param branchId 分支事务 ID
     * @return 防卫日志记录（若不存在返回 Optional.empty()）
     */
    Optional<TccFenceRecord> findByXidAndBranchId(String xid, Long branchId);

    /**
     * 插入新的 TCC Fence 记录
     *
     * @param record 防卫日志记录
     * @return 插入成功返回 true；若主键已存在冲突返回 false
     */
    boolean insertRecord(TccFenceRecord record);

    /**
     * 乐观原子更新防卫日志状态
     *
     * @param xid       全局事务 ID
     * @param branchId  分支事务 ID
     * @param oldStatus 原状态（乐观并发条件）
     * @param newStatus 新状态
     * @return 更新成功返回 true，若原状态不匹配返回 false
     */
    boolean updateStatus(String xid, Long branchId, TccFenceStatus oldStatus, TccFenceStatus newStatus);

    /**
     * 删除指定防卫日志
     *
     * @param xid      全局事务 ID
     * @param branchId 分支事务 ID
     * @return 删除成功返回 true，不存在返回 false
     */
    boolean deleteRecord(String xid, Long branchId);

    /**
     * 批量删除满足截止时间与指定状态列表的防卫日志记录
     *
     * @param beforeTime 截止时间（修改时间或创建时间早于此时间的记录将被清理）
     * @param statuses   目标状态列表（如 COMMITTED, ROLLBACKED 或 SUSPENDED）
     * @param limit      单批次最大删除记录数（必须大于 0）
     * @return 实际物理删除的记录条数
     */
    int deleteExpiredRecords(java.time.LocalDateTime beforeTime, java.util.List<TccFenceStatus> statuses, int limit);
}
