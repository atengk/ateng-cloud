package io.github.atengk.cloud.seata.tcc;

import io.github.atengk.cloud.seata.code.SeataResultCode;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * TCC Fence 业务防卫屏障组件
 * <p>
 * 依托本地分支事务日志仓储 {@link TccFenceRepository}，在 TCC 两阶段生命周期中
 * 实施原子判定与状态机转换，刚性拦截三大核心边缘异常：
 * <ol>
 *     <li><b>空回滚 (Empty Rollback)</b>：未执行 Try 时接收到 Cancel，原子插入 SUSPENDED 标记并跳过业务回滚；</li>
 *     <li><b>业务悬挂 (Hanging)</b>：Cancel 先于 Try 到达时，拒绝延迟到达的 Try 操作；</li>
 *     <li><b>重复提交与回滚 (Idempotent)</b>：多次重试 Confirm 或 Cancel 幂等放行与静默返回。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TccFenceBarrier {

    private static final Logger log = LoggerFactory.getLogger(TccFenceBarrier.class);

    private final TccFenceRepository repository;

    public TccFenceBarrier(TccFenceRepository repository) {
        this.repository = Objects.requireNonNull(repository, "TccFenceRepository 必须非空");
    }

    /**
     * 一阶段 Try 业务防卫执行
     *
     * @param xid            全局事务 ID
     * @param branchId       分支事务 ID
     * @param actionName     TCC 动作名称
     * @param targetCallback 业务一阶段 Try 回调
     * @param <T>            返回值类型
     * @return 业务 Try 执行结果
     * @throws Throwable 业务抛出的异常或防卫拦截异常
     */
    public <T> T prepareFence(String xid, Long branchId, String actionName, TccFenceCallback<T> targetCallback) throws Throwable {
        validateId(xid, branchId);
        Objects.requireNonNull(targetCallback, "targetCallback 必须非空");

        // 1. 检查是否已被 Cancel 标记为空回滚悬挂
        Optional<TccFenceRecord> existingOpt = repository.findByXidAndBranchId(xid, branchId);
        if (existingOpt.isPresent()) {
            TccFenceRecord existing = existingOpt.get();
            if (existing.getStatus() == TccFenceStatus.SUSPENDED) {
                log.warn("检测到业务悬挂风险：分支事务 [{}#{}] 已先期执行 Cancel 悬挂阻断，拒绝延迟到达的 Try 操作！", xid, branchId);
                throw new TccFenceHangingException(
                        "分支事务已被空回滚标记为悬挂状态，刚性拒绝延迟到达的 Try 操作！",
                        xid, branchId
                );
            }
            if (existing.getStatus() == TccFenceStatus.TRIED) {
                log.warn("分支事务 [{}#{}] 一阶段已执行，拒绝重复 Try", xid, branchId);
                throw new TccFenceException(
                        SeataResultCode.TCC_FENCE_DUPLICATE_CALL,
                        "分支事务一阶段已执行，拒绝重复 Try",
                        xid, branchId
                );
            }
            log.error("分支事务 [{}#{}] 处于非法终态 [{}]，拒绝 Try", xid, branchId, existing.getStatus());
            throw new TccFenceException(
                    SeataResultCode.TCC_FENCE_STATE_ERROR,
                    "分支事务处于非法终态 [" + existing.getStatus() + "]，拒绝 Try",
                    xid, branchId
            );
        }

        // 2. 尝试原子插入 TRIED 状态记录
        TccFenceRecord newRecord = new TccFenceRecord(xid, branchId, actionName, TccFenceStatus.TRIED);
        boolean inserted = repository.insertRecord(newRecord);
        if (!inserted) {
            // 并发插入冲突，重新查验状态
            TccFenceRecord conflictRecord = repository.findByXidAndBranchId(xid, branchId)
                    .orElseThrow(() -> new TccFenceException("并发插入冲突但记录未查询到", xid, branchId));
            if (conflictRecord.getStatus() == TccFenceStatus.SUSPENDED) {
                log.warn("并发检测到业务悬挂：分支事务 [{}#{}] 已处于悬挂状态，拦截延迟 Try！", xid, branchId);
                throw new TccFenceHangingException(
                        "分支事务已被空回滚标记为悬挂状态，刚性拒绝延迟到达的 Try 操作！",
                        xid, branchId
                );
            }
            throw new TccFenceException(
                    SeataResultCode.TCC_FENCE_DUPLICATE_CALL,
                    "分支事务并发插入冲突，拒绝重复 Try",
                    xid, branchId
            );
        }

        log.debug("TCC Fence 一阶段 Try 防卫成功插入 TRIED 记录: [{}#{}]", xid, branchId);

        // 3. 执行业务一阶段逻辑
        try {
            return targetCallback.execute();
        } catch (Throwable t) {
            log.warn("TCC Fence 一阶段业务逻辑执行异常: [{}#{}]", xid, branchId, t);
            throw t;
        }
    }

    /**
     * 一阶段 Try 业务防卫执行（重载支持 BusinessActionContext）
     *
     * @param actionContext  业务上下文
     * @param actionName     TCC 动作名称
     * @param targetCallback 业务一阶段 Try 回调
     * @param <T>            返回值类型
     * @return 业务 Try 执行结果
     * @throws Throwable 业务异常或防卫异常
     */
    public <T> T prepareFence(BusinessActionContext actionContext, String actionName, TccFenceCallback<T> targetCallback) throws Throwable {
        Objects.requireNonNull(actionContext, "BusinessActionContext 必须非空");
        return prepareFence(actionContext.getXid(), actionContext.getBranchId(), actionName, targetCallback);
    }

    /**
     * 二阶段 Confirm 业务防卫执行
     *
     * @param xid            全局事务 ID
     * @param branchId       分支事务 ID
     * @param targetCallback 业务二阶段 Confirm 回调
     * @return 提交成功返回 true
     * @throws Throwable 业务异常或防卫异常
     */
    public boolean commitFence(String xid, Long branchId, TccFenceCallback<Boolean> targetCallback) throws Throwable {
        validateId(xid, branchId);
        Objects.requireNonNull(targetCallback, "targetCallback 必须非空");

        Optional<TccFenceRecord> recordOpt = repository.findByXidAndBranchId(xid, branchId);
        if (recordOpt.isEmpty()) {
            log.error("TCC Fence Confirm 失败：分支事务记录不存在 [{}#{}]", xid, branchId);
            throw new TccFenceException(
                    SeataResultCode.TCC_FENCE_RECORD_NOT_EXISTS,
                    "TCC 分支事务记录不存在，无法执行 Confirm",
                    xid, branchId
            );
        }

        TccFenceRecord record = recordOpt.get();

        // 幂等防御：若已提交，静默返回 true
        if (record.getStatus() == TccFenceStatus.COMMITTED) {
            log.info("TCC Fence 检测到重复提交，幂等放行: [{}#{}]", xid, branchId);
            return true;
        }

        // 状态合法性防御：非 TRIED 状态禁止提交
        if (record.getStatus() != TccFenceStatus.TRIED) {
            log.error("TCC Fence Confirm 失败：分支事务处于不可提交状态 [{}#{}] 状态: [{}]", xid, branchId, record.getStatus());
            throw new TccFenceException(
                    SeataResultCode.TCC_FENCE_STATE_ERROR,
                    "分支事务处于不可提交状态 [" + record.getStatus() + "]，无法执行 Confirm",
                    xid, branchId
            );
        }

        // CAS 更新为 COMMITTED
        boolean updated = repository.updateStatus(xid, branchId, TccFenceStatus.TRIED, TccFenceStatus.COMMITTED);
        if (!updated) {
            // 并发推进，再次自检
            TccFenceRecord current = repository.findByXidAndBranchId(xid, branchId).orElse(null);
            if (current != null && current.getStatus() == TccFenceStatus.COMMITTED) {
                log.info("TCC Fence 并发重复提交检测成功，幂等放行: [{}#{}]", xid, branchId);
                return true;
            }
            throw new TccFenceException(
                    SeataResultCode.TCC_FENCE_STATE_ERROR,
                    "TCC Fence Confirm CAS 状态转移失败",
                    xid, branchId
            );
        }

        log.debug("TCC Fence Confirm 状态成功转移为 COMMITTED: [{}#{}]", xid, branchId);

        // 执行业务 Confirm
        Boolean result = targetCallback.execute();
        return result != null && result;
    }

    /**
     * 二阶段 Confirm 业务防卫执行（重载支持 BusinessActionContext）
     *
     * @param actionContext  业务上下文
     * @param targetCallback 业务二阶段 Confirm 回调
     * @return 提交成功返回 true
     * @throws Throwable 业务异常或防卫异常
     */
    public boolean commitFence(BusinessActionContext actionContext, TccFenceCallback<Boolean> targetCallback) throws Throwable {
        Objects.requireNonNull(actionContext, "BusinessActionContext 必须非空");
        return commitFence(actionContext.getXid(), actionContext.getBranchId(), targetCallback);
    }

    /**
     * 二阶段 Cancel 业务防卫执行
     *
     * @param xid            全局事务 ID
     * @param branchId       分支事务 ID
     * @param actionName     TCC 动作名称
     * @param targetCallback 业务二阶段 Cancel 回调
     * @return 回滚成功返回 true
     * @throws Throwable 业务异常或防卫异常
     */
    public boolean rollbackFence(String xid, Long branchId, String actionName, TccFenceCallback<Boolean> targetCallback) throws Throwable {
        validateId(xid, branchId);
        Objects.requireNonNull(targetCallback, "targetCallback 必须非空");

        Optional<TccFenceRecord> recordOpt = repository.findByXidAndBranchId(xid, branchId);

        // 空回滚防卫：若无记录，说明 Try 尚未执行！原子插入 SUSPENDED 标记占位
        if (recordOpt.isEmpty()) {
            TccFenceRecord suspendedRecord = new TccFenceRecord(xid, branchId, actionName, TccFenceStatus.SUSPENDED);
            boolean inserted = repository.insertRecord(suspendedRecord);
            if (inserted) {
                log.info("TCC Fence 触发空回滚防卫：成功插入 SUSPENDED 标记，静默跳过业务补偿 [{}#{}]", xid, branchId);
                return true;
            }
            // 并发插入冲突，重查
            recordOpt = repository.findByXidAndBranchId(xid, branchId);
            if (recordOpt.isEmpty()) {
                throw new TccFenceException("空回滚并发插入冲突但记录未查询到", xid, branchId);
            }
        }

        TccFenceRecord record = recordOpt.get();

        // 幂等防御：若已回滚或已悬挂，静默返回 true
        if (record.getStatus() == TccFenceStatus.ROLLBACKED || record.getStatus() == TccFenceStatus.SUSPENDED) {
            log.info("TCC Fence 检测到重复回滚，幂等放行: [{}#{}] 状态: [{}]", xid, branchId, record.getStatus());
            return true;
        }

        // 状态合法性防御：若已 COMMITTED，严禁回滚
        if (record.getStatus() == TccFenceStatus.COMMITTED) {
            log.error("TCC Fence Cancel 失败：分支事务已提交，严禁执行回滚 [{}#{}]", xid, branchId);
            throw new TccFenceException(
                    SeataResultCode.TCC_FENCE_STATE_ERROR,
                    "分支事务已提交，严禁执行 Cancel 回滚",
                    xid, branchId
            );
        }

        // CAS 更新为 ROLLBACKED
        boolean updated = repository.updateStatus(xid, branchId, TccFenceStatus.TRIED, TccFenceStatus.ROLLBACKED);
        if (!updated) {
            TccFenceRecord current = repository.findByXidAndBranchId(xid, branchId).orElse(null);
            if (current != null && (current.getStatus() == TccFenceStatus.ROLLBACKED || current.getStatus() == TccFenceStatus.SUSPENDED)) {
                log.info("TCC Fence 并发重复回滚检测成功，幂等放行: [{}#{}]", xid, branchId);
                return true;
            }
            throw new TccFenceException(
                    SeataResultCode.TCC_FENCE_STATE_ERROR,
                    "TCC Fence Cancel CAS 状态转移失败",
                    xid, branchId
            );
        }

        log.debug("TCC Fence Cancel 状态成功转移为 ROLLBACKED: [{}#{}]", xid, branchId);

        // 执行业务补偿 Cancel
        Boolean result = targetCallback.execute();
        return result != null && result;
    }

    /**
     * 二阶段 Cancel 业务防卫执行（重载支持 BusinessActionContext）
     *
     * @param actionContext  业务上下文
     * @param targetCallback 业务二阶段 Cancel 回调
     * @return 回滚成功返回 true
     * @throws Throwable 业务异常或防卫异常
     */
    public boolean rollbackFence(BusinessActionContext actionContext, TccFenceCallback<Boolean> targetCallback) throws Throwable {
        Objects.requireNonNull(actionContext, "BusinessActionContext 必须非空");
        return rollbackFence(actionContext.getXid(), actionContext.getBranchId(), actionContext.getActionName(), targetCallback);
    }

    public TccFenceRepository getRepository() {
        return repository;
    }

    private void validateId(String xid, Long branchId) {
        if (xid == null || xid.isBlank()) {
            throw new IllegalArgumentException("xid 必须非空");
        }
        if (branchId == null) {
            throw new IllegalArgumentException("branchId 必须非空");
        }
    }
}
