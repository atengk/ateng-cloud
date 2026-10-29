package io.github.atengk.cloud.sample.seata;

import io.github.atengk.cloud.sample.seata.service.AccountService;
import io.github.atengk.cloud.seata.tcc.TccFenceBarrier;
import io.github.atengk.cloud.seata.tcc.TccFenceHangingException;
import io.github.atengk.cloud.seata.tcc.TccFenceRecord;
import io.github.atengk.cloud.seata.tcc.TccFenceStatus;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCC Fence 业务防卫屏障端到端集成实测
 * <p>
 * 验证本地防卫表 tcc_fence_log 在实际 Spring 上下文与真实数据库下的防卫行为：
 * 1. 正常 Try -> Confirm 提交流转
 * 2. 正常 Try -> Cancel 回滚补偿流转
 * 3. 刚性拦截“空回滚 (Empty Rollback)”
 * 4. 刚性拦截“业务悬挂 (Hanging)”
 * 5. 幂等放行“重复提交与重复回滚 (Idempotent)”
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest(classes = SampleSeataApplication.class)
@DisplayName("TCC Fence 防卫屏障端到端集成测试")
class SampleSeataTccFenceIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TccFenceBarrier tccFenceBarrier;

    private static final String USER_ID = "tcc_user_001";
    private static final String XID = "127.0.0.1:8091:112233445566778899";

    @BeforeEach
    void setUp() {
        RootContext.bind(XID);
        accountService.initAccount(USER_ID, 1000);
    }

    @AfterEach
    void tearDown() {
        if (RootContext.inGlobalTransaction()) {
            RootContext.unbind();
        }
    }

    private BusinessActionContext createActionContext(Long branchId, int money) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", USER_ID);
        params.put("money", money);
        return new BusinessActionContext(XID, String.valueOf(branchId), params);
    }

    @Test
    @DisplayName("测试 TCC 正常两阶段提交流转：Try 扣减，Confirm 确认")
    void shouldExecuteNormalTccCommit() throws Throwable {
        Long branchId = 1001L;
        BusinessActionContext context = createActionContext(branchId, 200);

        // 1. Try: 扣减 200 元
        boolean trySuccess = accountService.prepareDebitTcc(context, USER_ID, 200);
        assertThat(trySuccess).isTrue();
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(800);

        // 2. Confirm: 确认提交
        boolean commitSuccess = accountService.commit(context);
        assertThat(commitSuccess).isTrue();
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(800);

        // 校验防卫日志终态为 COMMITTED
        TccFenceRecord record = tccFenceBarrier.getRepository().findByXidAndBranchId(XID, branchId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(TccFenceStatus.COMMITTED);
    }

    @Test
    @DisplayName("测试 TCC 正常两阶段回滚流转：Try 扣减，Cancel 补偿恢复")
    void shouldExecuteNormalTccRollback() throws Throwable {
        Long branchId = 1002L;
        BusinessActionContext context = createActionContext(branchId, 300);

        // 1. Try: 扣减 300 元
        boolean trySuccess = accountService.prepareDebitTcc(context, USER_ID, 300);
        assertThat(trySuccess).isTrue();
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(700);

        // 2. Cancel: 补偿回滚
        boolean rollbackSuccess = accountService.rollback(context);
        assertThat(rollbackSuccess).isTrue();
        // 关键断言：余额恢复为 1000 元
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(1000);

        // 校验防卫日志终态为 ROLLBACKED
        TccFenceRecord record = tccFenceBarrier.getRepository().findByXidAndBranchId(XID, branchId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(TccFenceStatus.ROLLBACKED);
    }

    @Test
    @DisplayName("刚性防卫“空回滚”：未执行 Try 直接 Cancel 自动标记 SUSPENDED 并跳过业务补偿")
    void shouldHandleEmptyRollback() {
        Long branchId = 1003L;
        BusinessActionContext context = createActionContext(branchId, 500);

        // 未执行 Try，直接接收到 Cancel 回滚通知
        boolean rollbackSuccess = accountService.rollback(context);
        assertThat(rollbackSuccess).isTrue();

        // 关键断言：空回滚时不得调用补偿方法，账户余额保持 1000（不得虚增至 1500）！
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(1000);

        // 关键断言：防卫日志记录已原子占位为 SUSPENDED
        TccFenceRecord record = tccFenceBarrier.getRepository().findByXidAndBranchId(XID, branchId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(TccFenceStatus.SUSPENDED);
    }

    @Test
    @DisplayName("刚性防卫“业务悬挂”：Cancel 先于 Try 到达时，拒绝延迟到达的 Try 操作")
    void shouldPreventBusinessHanging() {
        Long branchId = 1004L;
        BusinessActionContext context = createActionContext(branchId, 400);

        // 1. Cancel 先行到达，触发空回滚防卫占位
        boolean rollbackSuccess = accountService.rollback(context);
        assertThat(rollbackSuccess).isTrue();

        // 2. 延迟的 Try 随后到达，防卫屏障应刚性拦截并抛出 TccFenceHangingException
        assertThatThrownBy(() -> accountService.prepareDebitTcc(context, USER_ID, 400))
                .isInstanceOf(TccFenceHangingException.class)
                .hasMessageContaining("悬挂");

        // 关键断言：被拦截的 Try 严禁执行，余额仍为 1000！
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(1000);
    }

    @Test
    @DisplayName("刚性防卫“重复提交与回滚幂等”：Confirm 与 Cancel 重复重试幂等放行")
    void shouldEnsureIdempotentExecution() throws Throwable {
        Long branchId = 1005L;
        BusinessActionContext context = createActionContext(branchId, 100);

        accountService.prepareDebitTcc(context, USER_ID, 100);
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(900);

        // 第一次 Confirm
        boolean firstCommit = accountService.commit(context);
        assertThat(firstCommit).isTrue();

        // 第二次 Confirm（重试）
        boolean secondCommit = accountService.commit(context);
        assertThat(secondCommit).isTrue();
        // 关键断言：幂等放行，余额依然是 900
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(900);

        // 测试 Cancel 幂等性
        Long branchId2 = 1006L;
        BusinessActionContext context2 = createActionContext(branchId2, 100);
        accountService.prepareDebitTcc(context2, USER_ID, 100);
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(800);

        // 第一次 Cancel
        boolean firstCancel = accountService.rollback(context2);
        assertThat(firstCancel).isTrue();
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(900);

        // 第二次 Cancel（重试）
        boolean secondCancel = accountService.rollback(context2);
        assertThat(secondCancel).isTrue();
        // 关键断言：重复回滚不重复补回余额，余额仍为 900
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(900);
    }
}
