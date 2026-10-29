package io.github.atengk.cloud.seata.tcc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 JDBC 数据源的 TCC Fence 本地日志仓储实现
 * <p>
 * 直连本地物理数据库执行对 {@code tcc_fence_log} 表的原子操作，
 * 采用原生 ANSI-SQL 确保对主流关系型数据库（MySQL, PostgreSQL, Oracle, H2）的弹性兼容。
 * 针对连接获取接入 {@link DataSourceUtils}，无缝协同受管 Spring 本地事务。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class JdbcTccFenceRepository implements TccFenceRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcTccFenceRepository.class);

    public static final String DEFAULT_LOG_TABLE_NAME = "tcc_fence_log";

    private final DataSource dataSource;
    private final String tableName;

    public JdbcTccFenceRepository(DataSource dataSource) {
        this(dataSource, DEFAULT_LOG_TABLE_NAME);
    }

    public JdbcTccFenceRepository(DataSource dataSource, String tableName) {
        this.dataSource = Objects.requireNonNull(dataSource, "DataSource 必须非空");
        this.tableName = (tableName != null && !tableName.isBlank()) ? tableName : DEFAULT_LOG_TABLE_NAME;
    }

    @Override
    public Optional<TccFenceRecord> findByXidAndBranchId(String xid, Long branchId) {
        if (xid == null || branchId == null) {
            return Optional.empty();
        }
        String sql = "SELECT xid, branch_id, action_name, status, gmt_create, gmt_modified FROM "
                + tableName + " WHERE xid = ? AND branch_id = ?";

        Connection conn = null;
        try {
            conn = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, xid);
                ps.setLong(2, branchId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        TccFenceRecord record = new TccFenceRecord();
                        record.setXid(rs.getString("xid"));
                        record.setBranchId(rs.getLong("branch_id"));
                        record.setActionName(rs.getString("action_name"));
                        record.setStatus(TccFenceStatus.fromCode(rs.getInt("status")));
                        Timestamp createTs = rs.getTimestamp("gmt_create");
                        if (createTs != null) {
                            record.setGmtCreate(createTs.toLocalDateTime());
                        }
                        Timestamp modTs = rs.getTimestamp("gmt_modified");
                        if (modTs != null) {
                            record.setGmtModified(modTs.toLocalDateTime());
                        }
                        return Optional.of(record);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("查询 TCC Fence 日志失败 [{}#{}]", xid, branchId, e);
            throw new TccFenceException("查询 TCC Fence 日志失败: " + e.getMessage(), xid, branchId);
        } finally {
            if (conn != null) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean insertRecord(TccFenceRecord record) {
        Objects.requireNonNull(record, "record 必须非空");
        String sql = "INSERT INTO " + tableName
                + " (xid, branch_id, action_name, status, gmt_create, gmt_modified) VALUES (?, ?, ?, ?, ?, ?)";

        Connection conn = null;
        try {
            conn = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, record.getXid());
                ps.setLong(2, record.getBranchId());
                ps.setString(3, record.getActionName() != null ? record.getActionName() : "");
                ps.setInt(4, record.getStatus().getCode());
                Timestamp now = Timestamp.valueOf(record.getGmtCreate() != null ? record.getGmtCreate() : LocalDateTime.now());
                ps.setTimestamp(5, now);
                ps.setTimestamp(6, now);

                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            // 捕获主键冲突异常（MySQL 1062, 标准 SQLState 23000 / 23xxx 完整唯一键冲突）
            String sqlState = e.getSQLState();
            int errorCode = e.getErrorCode();
            if (errorCode == 1062 || (sqlState != null && sqlState.startsWith("23"))) {
                log.debug("插入 TCC Fence 日志主键冲突 [{}#{}], 状态: [{}]",
                        record.getXid(), record.getBranchId(), record.getStatus());
                return false;
            }
            log.error("插入 TCC Fence 日志异常 [{}#{}]", record.getXid(), record.getBranchId(), e);
            throw new TccFenceException("插入 TCC Fence 日志失败: " + e.getMessage(), record.getXid(), record.getBranchId());
        } finally {
            if (conn != null) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    @Override
    public boolean updateStatus(String xid, Long branchId, TccFenceStatus oldStatus, TccFenceStatus newStatus) {
        if (xid == null || branchId == null || oldStatus == null || newStatus == null) {
            return false;
        }
        String sql = "UPDATE " + tableName
                + " SET status = ?, gmt_modified = ? WHERE xid = ? AND branch_id = ? AND status = ?";

        Connection conn = null;
        try {
            conn = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, newStatus.getCode());
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                ps.setString(3, xid);
                ps.setLong(4, branchId);
                ps.setInt(5, oldStatus.getCode());

                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.error("CAS 更新 TCC Fence 日志状态失败 [{}#{}] 原状态: [{}] -> 新状态: [{}]",
                    xid, branchId, oldStatus, newStatus, e);
            throw new TccFenceException("CAS 更新 TCC Fence 日志状态失败: " + e.getMessage(), xid, branchId);
        } finally {
            if (conn != null) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    @Override
    public boolean deleteRecord(String xid, Long branchId) {
        if (xid == null || branchId == null) {
            return false;
        }
        String sql = "DELETE FROM " + tableName + " WHERE xid = ? AND branch_id = ?";

        Connection conn = null;
        try {
            conn = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, xid);
                ps.setLong(2, branchId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.error("删除 TCC Fence 日志失败 [{}#{}]", xid, branchId, e);
            throw new TccFenceException("删除 TCC Fence 日志失败: " + e.getMessage(), xid, branchId);
        } finally {
            if (conn != null) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public String getTableName() {
        return tableName;
    }
}
