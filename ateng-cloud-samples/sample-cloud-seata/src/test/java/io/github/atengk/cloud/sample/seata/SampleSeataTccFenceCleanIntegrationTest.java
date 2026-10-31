package io.github.atengk.cloud.sample.seata;

import io.github.atengk.cloud.seata.tcc.TccFenceCleanResult;
import io.github.atengk.cloud.seata.tcc.TccFenceLogCleanScheduler;
import io.github.atengk.cloud.seata.tcc.TccFenceRepository;
import io.github.atengk.cloud.seata.tcc.TccFenceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCC 防卫日志分级生命周期批量清理端到端集成实测
 * <p>
 * 验证真实 H2 数据库下日志清理策略：
 * 1. 终态记录（COMMITTED / ROLLBACKED）：超出 logTtl（默认 3 天）准时清理；未超时记录安全留存；
 * 2. 悬挂记录（SUSPENDED）：长效留存 3 倍 logTtl（默认 9 天），杜绝延迟 Try 到达引发业务悬挂；超出 9 天后安全回收；
 * 3. 中间状态（TRIED）：无论时间多久坚决不执行物理删除；
 * 4. 批量分批限制：根据 cleanBatchSize 分批执行，避免大事务长事务锁表。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = SampleSeataApplication.class)
@DisplayName("TCC 防卫日志分级生命周期批量清理端到端实测")
class SampleSeataTccFenceCleanIntegrationTest {

    @Autowired
    private TccFenceLogCleanScheduler cleanScheduler;

    @Autowired
    private TccFenceRepository fenceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanFenceTable() {
        jdbcTemplate.update("DELETE FROM tcc_fence_log");
    }

    private void insertLogRecord(String xid, long branchId, TccFenceStatus status, LocalDateTime modifiedTime) {
        jdbcTemplate.update(
                "INSERT INTO tcc_fence_log (xid, branch_id, action_name, status, gmt_create, gmt_modified) VALUES (?, ?, ?, ?, ?, ?)",
                xid, branchId, "sampleAction", status.getCode(),
                Timestamp.valueOf(modifiedTime.minusHours(1)), Timestamp.valueOf(modifiedTime));
    }

    @Test
    @DisplayName("测试分级生命周期清理：终态超期清理、悬挂长效留存超期清理、活跃状态永不清理")
    void shouldExecuteHierarchicalLogClean() {
        LocalDateTime now = LocalDateTime.now();

        // 1. 终态超期记录：修改于 4 天前（> 3 天），应被清理
        insertLogRecord("xid_term_exp_1", 101L, TccFenceStatus.COMMITTED, now.minusDays(4));
        insertLogRecord("xid_term_exp_2", 102L, TccFenceStatus.ROLLBACKED, now.minusDays(5));

        // 2. 终态新鲜记录：修改于 1 天前（< 3 天），必须保留
        insertLogRecord("xid_term_fresh", 103L, TccFenceStatus.COMMITTED, now.minusDays(1));

        // 3. 悬挂保护期记录：修改于 5 天前（> 3 天但 <= 9 天），根据 3x 悬挂留存规则，必须长效保留！
        insertLogRecord("xid_susp_protected", 104L, TccFenceStatus.SUSPENDED, now.minusDays(5));

        // 4. 悬挂超期记录：修改于 10 天前（> 9 天），超出 3x 保护窗口，应被清理
        insertLogRecord("xid_susp_expired", 105L, TccFenceStatus.SUSPENDED, now.minusDays(10));

        // 5. 活跃中状态记录：修改于 30 天前（TRIED），永不清理
        insertLogRecord("xid_tried_old", 106L, TccFenceStatus.TRIED, now.minusDays(30));

        // 执行分级清理调度任务
        TccFenceCleanResult result = cleanScheduler.clean();

        // 强断言清理统计结果
        assertThat(result.getTerminalDeleted()).isEqualTo(2);
        assertThat(result.getSuspendedDeleted()).isEqualTo(1);
        assertThat(result.getTotalDeleted()).isEqualTo(3);

        // 强断言数据库实际保留数据
        Integer countExp1 = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid = 'xid_term_exp_1'", Integer.class);
        Integer countExp2 = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid = 'xid_term_exp_2'", Integer.class);
        Integer countSuspExp = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid = 'xid_susp_expired'", Integer.class);

        assertThat(countExp1).isZero();
        assertThat(countExp2).isZero();
        assertThat(countSuspExp).isZero();

        Integer countFresh = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid = 'xid_term_fresh'", Integer.class);
        Integer countSuspProtected = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid = 'xid_susp_protected'", Integer.class);
        Integer countTried = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid = 'xid_tried_old'", Integer.class);

        assertThat(countFresh).isEqualTo(1);
        assertThat(countSuspProtected).isEqualTo(1);
        assertThat(countTried).isEqualTo(1);
    }

    @Test
    @DisplayName("测试批量分页删除限制：单批次限制严格生效")
    void shouldRespectBatchSizeLimit() {
        LocalDateTime fourDaysAgo = LocalDateTime.now().minusDays(4);

        // 插入 5 条超期 COMMITTED 记录
        for (int i = 1; i <= 5; i++) {
            insertLogRecord("batch_xid_" + i, 200L + i, TccFenceStatus.COMMITTED, fourDaysAgo);
        }

        // 调用底层 SPI，限制批次大小为 2
        int deleted = fenceRepository.deleteExpiredRecords(
                LocalDateTime.now().minusDays(3),
                List.of(TccFenceStatus.COMMITTED),
                2);

        // 强断言：单次精确删除 2 条
        assertThat(deleted).isEqualTo(2);

        Integer remaining = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid LIKE 'batch_xid_%'", Integer.class);
        assertThat(remaining).isEqualTo(3);
    }
}
