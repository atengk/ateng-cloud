package io.github.atengk.cloud.seata.tcc;

import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编程式 SeataTccFenceTemplate 模板功能单测
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("SeataTccFenceTemplate 模板单测")
class SeataTccFenceTemplateTest {

    private InMemoryTccFenceRepository repository;
    private SeataTccFenceTemplate template;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTccFenceRepository();
        TccFenceBarrier barrier = new TccFenceBarrier(repository);
        template = new SeataTccFenceTemplate(barrier);
    }

    @Test
    @DisplayName("测试模板 executePrepare 与 executeCommit 正常流转")
    void shouldExecutePrepareAndCommitSuccessfully() throws Throwable {
        String xid = "xid-tpl-1";
        Long branchId = 301L;
        String actionName = "orderTcc";

        AtomicInteger tryCount = new AtomicInteger(0);
        AtomicInteger commitCount = new AtomicInteger(0);

        String result = template.executePrepare(xid, branchId, actionName, () -> {
            tryCount.incrementAndGet();
            return "SUCCESS_TRY";
        });
        assertThat(result).isEqualTo("SUCCESS_TRY");
        assertThat(tryCount.get()).isEqualTo(1);

        boolean committed = template.executeCommit(xid, branchId, () -> {
            commitCount.incrementAndGet();
            return true;
        });
        assertThat(committed).isTrue();
        assertThat(commitCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试模板 executeRollback 正常回滚")
    void shouldExecuteRollbackSuccessfully() throws Throwable {
        String xid = "xid-tpl-2";
        Long branchId = 302L;
        String actionName = "payTcc";

        AtomicInteger rollbackCount = new AtomicInteger(0);

        template.executePrepare(xid, branchId, actionName, () -> "OK");

        boolean rollbacked = template.executeRollback(xid, branchId, actionName, () -> {
            rollbackCount.incrementAndGet();
            return true;
        });
        assertThat(rollbacked).isTrue();
        assertThat(rollbackCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试模板基于 BusinessActionContext 的一阶段与二阶段执行")
    void shouldExecuteWithBusinessActionContext() throws Throwable {
        String xid = "xid-tpl-3";
        Long branchId = 303L;
        String actionName = "stockTcc";

        BusinessActionContext context = new BusinessActionContext(xid, String.valueOf(branchId), Collections.emptyMap());

        AtomicInteger tryCount = new AtomicInteger(0);
        AtomicInteger confirmCount = new AtomicInteger(0);

        template.executePrepare(context, actionName, () -> {
            tryCount.incrementAndGet();
            return "OK";
        });
        assertThat(tryCount.get()).isEqualTo(1);

        template.executeCommit(context, () -> {
            confirmCount.incrementAndGet();
            return true;
        });
        assertThat(confirmCount.get()).isEqualTo(1);
    }
}
