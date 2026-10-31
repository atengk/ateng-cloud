package io.github.atengk.cloud.seata.tcc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("按截止时间与状态批量清理过期内存日志")
    void shouldDeleteExpiredRecordsInMemory() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oldTime = now.minusDays(5);
        LocalDateTime recentTime = now.minusHours(1);

        // 插入 2 条已过期终态记录
        repository.insertRecord(new TccFenceRecord("xid-old-1", 1L, "action", TccFenceStatus.COMMITTED, oldTime, oldTime));
        repository.insertRecord(new TccFenceRecord("xid-old-2", 2L, "action", TccFenceStatus.ROLLBACKED, oldTime, oldTime));
        // 插入 1 条已过期但状态为 TRIED 的记录（不应被终态清理）
        repository.insertRecord(new TccFenceRecord("xid-old-3", 3L, "action", TccFenceStatus.TRIED, oldTime, oldTime));
        // 插入 1 条近期 COMMITTED 记录（未过期）
        repository.insertRecord(new TccFenceRecord("xid-recent-1", 4L, "action", TccFenceStatus.COMMITTED, recentTime, recentTime));

        List<TccFenceStatus> targetStatuses = List.of(TccFenceStatus.COMMITTED, TccFenceStatus.ROLLBACKED);
        int deleted = repository.deleteExpiredRecords(now.minusDays(3), targetStatuses, 10);

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.findByXidAndBranchId("xid-old-1", 1L)).isEmpty();
        assertThat(repository.findByXidAndBranchId("xid-old-2", 2L)).isEmpty();
        assertThat(repository.findByXidAndBranchId("xid-old-3", 3L)).isPresent();
        assertThat(repository.findByXidAndBranchId("xid-recent-1", 4L)).isPresent();
    }

    @Test
    @DisplayName("批量清理受 limit 阈值硬限制")
    void shouldRespectLimitThresholdInMemory() {
        LocalDateTime oldTime = LocalDateTime.now().minusDays(5);

        repository.insertRecord(new TccFenceRecord("xid-limit-1", 1L, "action", TccFenceStatus.COMMITTED, oldTime, oldTime));
        repository.insertRecord(new TccFenceRecord("xid-limit-2", 2L, "action", TccFenceStatus.COMMITTED, oldTime, oldTime));
        repository.insertRecord(new TccFenceRecord("xid-limit-3", 3L, "action", TccFenceStatus.COMMITTED, oldTime, oldTime));

        int deleted = repository.deleteExpiredRecords(LocalDateTime.now(), List.of(TccFenceStatus.COMMITTED), 2);
        assertThat(deleted).isEqualTo(2);
    }

    @Test
    @DisplayName("批量清理非法参数防卫校验")
    void shouldValidateArgumentsOnDeleteExpired() {
        assertThatThrownBy(() -> repository.deleteExpiredRecords(null, List.of(TccFenceStatus.COMMITTED), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("beforeTime 必须非空");

        assertThatThrownBy(() -> repository.deleteExpiredRecords(LocalDateTime.now(), List.of(TccFenceStatus.COMMITTED), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit 必须大于 0");

        assertThat(repository.deleteExpiredRecords(LocalDateTime.now(), Collections.emptyList(), 10)).isEqualTo(0);
        assertThat(repository.deleteExpiredRecords(LocalDateTime.now(), null, 10)).isEqualTo(0);
    }
}
