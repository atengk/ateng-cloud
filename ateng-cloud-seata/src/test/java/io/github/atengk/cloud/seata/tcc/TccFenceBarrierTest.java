package io.github.atengk.cloud.seata.tcc;

import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCC Fence 业务防卫屏障核心单测
 * <p>
 * 全真覆盖 TCC 两阶段正常流转与三大边缘异常：
 * 1. 正常 Try -> Confirm 提交流转
 * 2. 正常 Try -> Cancel 回滚流转
 * 3. 刚性防卫“空回滚 (Empty Rollback)”
 * 4. 刚性防卫“业务悬挂 (Hanging)”
 * 5. 刚性防卫“重复提交与回滚幂等 (Idempotent)”
 * 6. 防卫“无 Try 的直接 Confirm 拦截”
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("TccFenceBarrier 业务防卫屏障状态机单测")
class TccFenceBarrierTest {

    private InMemoryTccFenceRepository repository;
    private TccFenceBarrier barrier;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTccFenceRepository();
        barrier = new TccFenceBarrier(repository);
    }

    @Test
    @DisplayName("场景1：正常生命周期 Try -> Confirm 顺利提交")
    void shouldCompleteNormalTryAndConfirm() throws Throwable {
        String xid = "xid-2001";
        Long branchId = 201L;
        String actionName = "deductStock";

        AtomicInteger tryCount = new AtomicInteger(0);
        AtomicInteger confirmCount = new AtomicInteger(0);

        // 1. 执行 Try
        String result = barrier.prepareFence(xid, branchId, actionName, () -> {
            tryCount.incrementAndGet();
            return "SUCCESS_TRY";
        });

        assertThat(result).isEqualTo("SUCCESS_TRY");
        assertThat(tryCount.get()).isEqualTo(1);
        assertThat(repository.findByXidAndBranchId(xid, branchId).get().getStatus())
                .isEqualTo(TccFenceStatus.TRIED);

        // 2. 执行 Confirm
        boolean committed = barrier.commitFence(xid, branchId, () -> {
            confirmCount.incrementAndGet();
            return true;
        });

        assertThat(committed).isTrue();
        assertThat(confirmCount.get()).isEqualTo(1);
        assertThat(repository.findByXidAndBranchId(xid, branchId).get().getStatus())
                .isEqualTo(TccFenceStatus.COMMITTED);
    }

    @Test
    @DisplayName("场景2：正常生命周期 Try -> Cancel 顺利回滚")
    void shouldCompleteNormalTryAndCancel() throws Throwable {
        String xid = "xid-2002";
        Long branchId = 202L;
        String actionName = "freezeBalance";

        AtomicInteger tryCount = new AtomicInteger(0);
        AtomicInteger rollbackCount = new AtomicInteger(0);

        // 1. 执行 Try
        barrier.prepareFence(xid, branchId, actionName, () -> {
            tryCount.incrementAndGet();
            return "SUCCESS";
        });

        // 2. 执行 Cancel
        boolean rollbacked = barrier.rollbackFence(xid, branchId, actionName, () -> {
            rollbackCount.incrementAndGet();
            return true;
        });

        assertThat(rollbacked).isTrue();
        assertThat(rollbackCount.get()).isEqualTo(1);
        assertThat(repository.findByXidAndBranchId(xid, branchId).get().getStatus())
                .isEqualTo(TccFenceStatus.ROLLBACKED);
    }

    @Test
    @DisplayName("场景3：刚性防御“空回滚”——未执行 Try 时接收到 Cancel 自动标记 SUSPENDED 并跳过业务回滚")
    void shouldHandleEmptyRollbackCorrectly() throws Throwable {
        String xid = "xid-2003";
        Long branchId = 203L;
        String actionName = "deductScore";

        AtomicInteger rollbackCount = new AtomicInteger(0);

        // 未执行 Try，直接接收到 Cancel
        boolean result = barrier.rollbackFence(xid, branchId, actionName, () -> {
            rollbackCount.incrementAndGet();
            return true;
        });

        assertThat(result).isTrue();
        // 关键断言：空回滚时不得调用实际业务补偿逻辑！
        assertThat(rollbackCount.get()).isEqualTo(0);

        // 关键断言：必须在本地生成 SUSPENDED 悬挂防卫标记
        TccFenceRecord record = repository.findByXidAndBranchId(xid, branchId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(TccFenceStatus.SUSPENDED);
    }

    @Test
    @DisplayName("场景4：刚性防御“业务悬挂”——Cancel 先于 Try 到达时，拒绝延迟到达的 Try 操作")
    void shouldRejectDelayedTryAfterCancelToPreventHanging() throws Throwable {
        String xid = "xid-2004";
        Long branchId = 204L;
        String actionName = "createCoupon";

        AtomicInteger rollbackCount = new AtomicInteger(0);
        AtomicInteger tryCount = new AtomicInteger(0);

        // 1. Cancel 先行到达，触发空回滚防卫
        barrier.rollbackFence(xid, branchId, actionName, () -> {
            rollbackCount.incrementAndGet();
            return true;
        });
        assertThat(rollbackCount.get()).isEqualTo(0);

        // 2. 延迟的 Try 随后到达，防卫屏障应刚性拦截并抛出 TccFenceHangingException
        assertThatThrownBy(() -> barrier.prepareFence(xid, branchId, actionName, () -> {
            tryCount.incrementAndGet();
            return "SHOULD_NOT_EXECUTE";
        }))
                .isInstanceOf(TccFenceHangingException.class)
                .hasMessageContaining("悬挂");

        // 关键断言：被拦截的 Try 业务逻辑严禁执行！
        assertThat(tryCount.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("场景5：刚性防御“重复提交”——Confirm 多次重试幂等放行，仅执行一次业务逻辑")
    void shouldEnsureIdempotentConfirm() throws Throwable {
        String xid = "xid-2005";
        Long branchId = 205L;
        String actionName = "reduceInventory";

        AtomicInteger confirmCount = new AtomicInteger(0);

        barrier.prepareFence(xid, branchId, actionName, () -> "OK");

        // 第一次 Confirm
        boolean firstCommit = barrier.commitFence(xid, branchId, () -> {
            confirmCount.incrementAndGet();
            return true;
        });
        assertThat(firstCommit).isTrue();
        assertThat(confirmCount.get()).isEqualTo(1);

        // 第二次 Confirm（重试）
        boolean secondCommit = barrier.commitFence(xid, branchId, () -> {
            confirmCount.incrementAndGet();
            return true;
        });
        assertThat(secondCommit).isTrue();
        // 关键断言：重复 Confirm 静默放行，业务逻辑不被重复调用！
        assertThat(confirmCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("场景6：刚性防御“重复回滚”——Cancel 多次重试幂等放行，仅执行一次业务补偿")
    void shouldEnsureIdempotentCancel() throws Throwable {
        String xid = "xid-2006";
        Long branchId = 206L;
        String actionName = "refundDeposit";

        AtomicInteger rollbackCount = new AtomicInteger(0);

        barrier.prepareFence(xid, branchId, actionName, () -> "OK");

        // 第一次 Cancel
        boolean firstCancel = barrier.rollbackFence(xid, branchId, actionName, () -> {
            rollbackCount.incrementAndGet();
            return true;
        });
        assertThat(firstCancel).isTrue();
        assertThat(rollbackCount.get()).isEqualTo(1);

        // 第二次 Cancel（重试）
        boolean secondCancel = barrier.rollbackFence(xid, branchId, actionName, () -> {
            rollbackCount.incrementAndGet();
            return true;
        });
        assertThat(secondCancel).isTrue();
        // 关键断言：重复 Cancel 静默放行，业务补偿不被重复调用！
        assertThat(rollbackCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("场景7：未执行 Try 直接 Confirm 应被刚性拒绝")
    void shouldRejectConfirmWithoutTry() {
        String xid = "xid-2007";
        Long branchId = 207L;

        assertThatThrownBy(() -> barrier.commitFence(xid, branchId, () -> true))
                .isInstanceOf(TccFenceException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("场景8：支持 BusinessActionContext 传参重载方法")
    void shouldSupportBusinessActionContext() throws Throwable {
        String xid = "xid-2008";
        Long branchId = 208L;
        String actionName = "transferAction";

        BusinessActionContext context = new BusinessActionContext(xid, String.valueOf(branchId), Collections.emptyMap());

        AtomicInteger tryCount = new AtomicInteger(0);
        AtomicInteger confirmCount = new AtomicInteger(0);

        barrier.prepareFence(context, actionName, () -> {
            tryCount.incrementAndGet();
            return "SUCCESS";
        });
        assertThat(tryCount.get()).isEqualTo(1);

        barrier.commitFence(context, () -> {
            confirmCount.incrementAndGet();
            return true;
        });
        assertThat(confirmCount.get()).isEqualTo(1);
    }
}
