package io.github.atengk.cloud.seata.tcc;

import io.github.atengk.cloud.seata.constant.SeataConstants;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;

/**
 * TCC 防卫日志数据表按需安全自愈初始化建表组件
 * <p>
 * 遵循安全受管原则，生产环境坚决默认关闭（{@code auto-init-table=false}），严防非授权越权 DDL 变更。
 * 当开发者在本地开发环境、H2 内存测试库或单测沙箱中显式开启此项时，
 * 在 Spring 容器启动阶段通过 JDBC 元数据（{@link DatabaseMetaData}）多重平滑探测当前物理库是否存在防卫表，
 * 若缺失则安全执行 ANSI-SQL 标准 DDL 建表自愈，赋能开箱即用的沙箱自洽验证。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class TccFenceTableInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(TccFenceTableInitializer.class);

    private final DataSource dataSource;
    private final SeataProperties properties;

    public TccFenceTableInitializer(DataSource dataSource, SeataProperties properties) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource 必须非空");
        this.properties = Objects.requireNonNull(properties, "properties 必须非空");
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.getTcc().isAutoInitTable()) {
            log.debug("TCC 防卫表按需安全自愈建表已关闭 (auto-init-table=false)，跳过元数据探测");
            return;
        }
        initializeTable();
    }

    /**
     * 执行 TCC 防卫表元数据探测与自愈初始化
     *
     * @return true 表示由于缺失而成功执行了自愈建表；false 表示表已存在或配置关闭，跳过建表
     */
    public boolean initializeTable() {
        String tableName = properties.getTcc().getTableName();
        if (tableName == null || tableName.isBlank()) {
            tableName = SeataConstants.DEFAULT_TCC_FENCE_TABLE_NAME;
        }

        Connection conn = null;
        try {
            conn = DataSourceUtils.getConnection(dataSource);
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema = null;
            try {
                schema = conn.getSchema();
            } catch (Throwable ignored) {
                // 部分旧版 JDBC 驱动不支持 getSchema()，静默降级为 null
            }

            if (checkTableExists(metaData, tableName, catalog, schema)) {
                log.info("TCC 防卫表 [{}] 已存在，跳过自愈建表", tableName);
                return false;
            }

            log.warn("检测到 TCC 防卫表 [{}] 不存在，auto-init-table 已开启，开始执行 ANSI-SQL 安全自愈建表", tableName);
            executeCreateTable(conn, tableName);
            log.info("TCC 防卫表 [{}] ANSI-SQL 安全自愈建表成功", tableName);
            return true;
        } catch (Exception e) {
            log.error("TCC 防卫表 [{}] 安全自愈建表失败: {}", tableName, e.getMessage(), e);
            throw new TccFenceException("TCC 防卫表自愈建表失败: " + e.getMessage(), null, null, e);
        } finally {
            if (conn != null) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    private boolean checkTableExists(DatabaseMetaData metaData, String tableName, String catalog, String schema) throws SQLException {
        String[] types = new String[]{"TABLE"};
        // 1. 原始大小写精确匹配
        try (ResultSet rs = metaData.getTables(catalog, schema, tableName, types)) {
            if (rs.next()) {
                return true;
            }
        }
        // 2. 全大写匹配 (Oracle, H2 等主流关系型库默认大写存储元数据)
        try (ResultSet rs = metaData.getTables(catalog, schema, tableName.toUpperCase(Locale.ROOT), types)) {
            if (rs.next()) {
                return true;
            }
        }
        // 3. 全小写匹配 (PostgreSQL, MySQL Linux 区分大小写模式)
        try (ResultSet rs = metaData.getTables(catalog, schema, tableName.toLowerCase(Locale.ROOT), types)) {
            if (rs.next()) {
                return true;
            }
        }
        // 4. 全表通配防卫扫描 (防止特定数据库驱动 catalog/schema 传参歧义导致的漏检)
        try (ResultSet rs = metaData.getTables(null, null, "%", types)) {
            while (rs.next()) {
                String actualName = rs.getString("TABLE_NAME");
                if (tableName.equalsIgnoreCase(actualName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void executeCreateTable(Connection conn, String tableName) throws SQLException {
        // ANSI-SQL 标准 CREATE TABLE 模板，兼容 MySQL, PostgreSQL, Oracle, H2
        String ddl = "CREATE TABLE " + tableName + " ("
                + "xid VARCHAR(128) NOT NULL, "
                + "branch_id BIGINT NOT NULL, "
                + "action_name VARCHAR(64) NOT NULL, "
                + "status INT NOT NULL, "
                + "gmt_create TIMESTAMP NOT NULL, "
                + "gmt_modified TIMESTAMP NOT NULL, "
                + "PRIMARY KEY (xid, branch_id)"
                + ")";

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }

        // 尝试创建辅助索引（平滑容错，即使某些数据库权限不足或语法特殊亦不阻断核心主表运作）
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE INDEX idx_" + tableName + "_modified ON " + tableName + " (gmt_modified)");
        } catch (SQLException e) {
            log.debug("创建 TCC 防卫表 gmt_modified 索引被跳过或已存在: {}", e.getMessage());
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE INDEX idx_" + tableName + "_status ON " + tableName + " (status)");
        } catch (SQLException e) {
            log.debug("创建 TCC 防卫表 status 索引被跳过或已存在: {}", e.getMessage());
        }
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public SeataProperties getProperties() {
        return properties;
    }
}
