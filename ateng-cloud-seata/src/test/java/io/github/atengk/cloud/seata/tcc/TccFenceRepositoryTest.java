package io.github.atengk.cloud.seata.tcc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCC Fence 仓储组件离线测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("TccFenceRepository 仓储功能单测")
class TccFenceRepositoryTest {

    private TccFenceRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTccFenceRepository();
    }

    @Test
    @DisplayName("插入新记录并根据 XID 与 branchId 成功查询")
    void shouldInsertAndFindRecord() {
        TccFenceRecord record = new TccFenceRecord("xid-1001", 101L, "orderAction", TccFenceStatus.TRIED);

        boolean inserted = repository.insertRecord(record);
        assertThat(inserted).isTrue();

        Optional<TccFenceRecord> found = repository.findByXidAndBranchId("xid-1001", 101L);
        assertThat(found).isPresent();
        assertThat(found.get().getXid()).isEqualTo("xid-1001");
        assertThat(found.get().getBranchId()).isEqualTo(101L);
        assertThat(found.get().getActionName()).isEqualTo("orderAction");
        assertThat(found.get().getStatus()).isEqualTo(TccFenceStatus.TRIED);
    }

    @Test
    @DisplayName("重复插入相同主键记录时应返回 false 阻断插入")
    void shouldRejectDuplicatePrimaryKeyInsert() {
        TccFenceRecord record1 = new TccFenceRecord("xid-1002", 102L, "stockAction", TccFenceStatus.SUSPENDED);
        TccFenceRecord record2 = new TccFenceRecord("xid-1002", 102L, "stockAction", TccFenceStatus.TRIED);

        assertThat(repository.insertRecord(record1)).isTrue();
        assertThat(repository.insertRecord(record2)).isFalse();

        Optional<TccFenceRecord> found = repository.findByXidAndBranchId("xid-1002", 102L);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TccFenceStatus.SUSPENDED);
    }

    @Test
    @DisplayName("原子 CAS 更新记录状态成功与失败校验")
    void shouldUpdateStatusWithCas() {
        TccFenceRecord record = new TccFenceRecord("xid-1003", 103L, "payAction", TccFenceStatus.TRIED);
        repository.insertRecord(record);

        // 错误的旧状态更新应失败
        boolean failUpdate = repository.updateStatus("xid-1003", 103L, TccFenceStatus.COMMITTED, TccFenceStatus.ROLLBACKED);
        assertThat(failUpdate).isFalse();

        // 正确的旧状态更新应成功
        boolean successUpdate = repository.updateStatus("xid-1003", 103L, TccFenceStatus.TRIED, TccFenceStatus.COMMITTED);
        assertThat(successUpdate).isTrue();

        Optional<TccFenceRecord> found = repository.findByXidAndBranchId("xid-1003", 103L);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TccFenceStatus.COMMITTED);
    }

    @Test
    @DisplayName("删除指定记录")
    void shouldDeleteRecord() {
        TccFenceRecord record = new TccFenceRecord("xid-1004", 104L, "accountAction", TccFenceStatus.COMMITTED);
        repository.insertRecord(record);

        assertThat(repository.deleteRecord("xid-1004", 104L)).isTrue();
        assertThat(repository.findByXidAndBranchId("xid-1004", 104L)).isEmpty();
        assertThat(repository.deleteRecord("xid-1004", 104L)).isFalse();
    }
}
