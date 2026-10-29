package io.github.atengk.cloud.seata.tcc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基于内存 H2 数据库的 JdbcTccFenceRepository 单测
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("JdbcTccFenceRepository JDBC 仓储单测")
class JdbcTccFenceRepositoryTest {

    private JdbcDataSource dataSource;
    private JdbcTccFenceRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:tcc_fence_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS tcc_fence_log (" +
                    "xid VARCHAR(128) NOT NULL, " +
                    "branch_id BIGINT NOT NULL, " +
                    "action_name VARCHAR(64) NOT NULL, " +
                    "status INT NOT NULL, " +
                    "gmt_create TIMESTAMP NOT NULL, " +
                    "gmt_modified TIMESTAMP NOT NULL, " +
                    "PRIMARY KEY (xid, branch_id)" +
                    ")");
        }

        repository = new JdbcTccFenceRepository(dataSource);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS tcc_fence_log");
        }
    }

    @Test
    @DisplayName("通过 JDBC 插入记录并精确查询")
    void shouldInsertAndFindRecordViaJdbc() {
        TccFenceRecord record = new TccFenceRecord("xid-jdbc-1", 101L, "orderAction", TccFenceStatus.TRIED);

        boolean inserted = repository.insertRecord(record);
        assertThat(inserted).isTrue();

        Optional<TccFenceRecord> found = repository.findByXidAndBranchId("xid-jdbc-1", 101L);
        assertThat(found).isPresent();
        assertThat(found.get().getXid()).isEqualTo("xid-jdbc-1");
        assertThat(found.get().getBranchId()).isEqualTo(101L);
        assertThat(found.get().getActionName()).isEqualTo("orderAction");
        assertThat(found.get().getStatus()).isEqualTo(TccFenceStatus.TRIED);
        assertThat(found.get().getGmtCreate()).isNotNull();
    }

    @Test
    @DisplayName("插入主键冲突记录应捕获唯一约束并返回 false")
    void shouldReturnFalseOnDuplicateKey() {
        TccFenceRecord record1 = new TccFenceRecord("xid-jdbc-2", 102L, "stockAction", TccFenceStatus.SUSPENDED);
        TccFenceRecord record2 = new TccFenceRecord("xid-jdbc-2", 102L, "stockAction", TccFenceStatus.TRIED);

        assertThat(repository.insertRecord(record1)).isTrue();
        assertThat(repository.insertRecord(record2)).isFalse();

        Optional<TccFenceRecord> found = repository.findByXidAndBranchId("xid-jdbc-2", 102L);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TccFenceStatus.SUSPENDED);
    }

    @Test
    @DisplayName("基于旧状态的原子 CAS 更新与失败验证")
    void shouldUpdateStatusViaJdbc() {
        TccFenceRecord record = new TccFenceRecord("xid-jdbc-3", 103L, "payAction", TccFenceStatus.TRIED);
        repository.insertRecord(record);

        // 错误的旧状态更新应失败
        boolean failUpdate = repository.updateStatus("xid-jdbc-3", 103L, TccFenceStatus.COMMITTED, TccFenceStatus.ROLLBACKED);
        assertThat(failUpdate).isFalse();

        // 正确的旧状态 CAS 应成功
        boolean successUpdate = repository.updateStatus("xid-jdbc-3", 103L, TccFenceStatus.TRIED, TccFenceStatus.COMMITTED);
        assertThat(successUpdate).isTrue();

        Optional<TccFenceRecord> found = repository.findByXidAndBranchId("xid-jdbc-3", 103L);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TccFenceStatus.COMMITTED);
    }

    @Test
    @DisplayName("删除记录并验证空查询")
    void shouldDeleteRecordViaJdbc() {
        TccFenceRecord record = new TccFenceRecord("xid-jdbc-4", 104L, "couponAction", TccFenceStatus.COMMITTED);
        repository.insertRecord(record);

        assertThat(repository.deleteRecord("xid-jdbc-4", 104L)).isTrue();
        assertThat(repository.findByXidAndBranchId("xid-jdbc-4", 104L)).isEmpty();
    }

    @Test
    @DisplayName("集成验证：TccFenceBarrier 在真实 JDBC 数据库下的空回滚与业务悬挂防卫")
    void shouldEnforceFenceBarrierWithJdbc() throws Throwable {
        TccFenceBarrier barrier = new TccFenceBarrier(repository);
        String xid = "xid-jdbc-fence-1";
        Long branchId = 501L;
        String actionName = "inventoryTcc";

        // 1. 空回滚：未执行 Try 直接 Cancel -> 插入 SUSPENDED
        boolean rollbackResult = barrier.rollbackFence(xid, branchId, actionName, () -> true);
        assertThat(rollbackResult).isTrue();

        // 2. 延迟到达的 Try 应被刚性拦截
        assertThatThrownBy(() -> barrier.prepareFence(xid, branchId, actionName, () -> "OK"))
                .isInstanceOf(TccFenceHangingException.class)
                .hasMessageContaining("悬挂");
    }
}
