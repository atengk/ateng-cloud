package io.github.atengk.cloud.seata.tcc;

import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCC 防卫表按需安全自愈建表初始化器单测
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("TccFenceTableInitializer 安全自愈建表初始化器单测")
class TccFenceTableInitializerTest {

    private JdbcDataSource dataSource;
    private SeataProperties properties;
    private TccFenceTableInitializer initializer;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:tcc_init_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        properties = new SeataProperties();
        properties.getTcc().setAutoInitTable(true);

        initializer = new TccFenceTableInitializer(dataSource, properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS tcc_fence_log");
            stmt.execute("DROP TABLE IF EXISTS my_custom_fence");
        }
    }

    @Test
    @DisplayName("auto-init-table 开启且表缺失时，自动执行 ANSI-SQL 建表自愈")
    void shouldCreateTableWhenTableNotExistsAndAutoInitEnabled() throws Exception {
        // 初始确保表不存在
        assertThat(isTableExists("tcc_fence_log")).isFalse();

        // 执行自愈初始化
        boolean initialized = initializer.initializeTable();
        assertThat(initialized).isTrue();

        // 断言表已成功自愈创建
        assertThat(isTableExists("tcc_fence_log")).isTrue();

        // 验证自愈生成的表能够正常执行 CRUD
        JdbcTccFenceRepository repository = new JdbcTccFenceRepository(dataSource);
        TccFenceRecord record = new TccFenceRecord("xid-init-1", 1001L, "initAction", TccFenceStatus.TRIED);
        assertThat(repository.insertRecord(record)).isTrue();

        Optional<TccFenceRecord> found = repository.findByXidAndBranchId("xid-init-1", 1001L);
        assertThat(found).isPresent();
        assertThat(found.get().getActionName()).isEqualTo("initAction");
        assertThat(found.get().getStatus()).isEqualTo(TccFenceStatus.TRIED);
    }

    @Test
    @DisplayName("当表已存在时，自愈初始化具备幂等性并静默跳过")
    void shouldSkipWhenTableAlreadyExists() {
        // 第一次执行：创建表
        boolean firstRun = initializer.initializeTable();
        assertThat(firstRun).isTrue();

        // 第二次执行：探测到已存在，幂等跳过
        boolean secondRun = initializer.initializeTable();
        assertThat(secondRun).isFalse();
    }

    @Test
    @DisplayName("auto-init-table 为 false 时坚决不执行元数据探测与 DDL 建表")
    void shouldDoNothingWhenAutoInitDisabled() throws Exception {
        properties.getTcc().setAutoInitTable(false);

        initializer.afterPropertiesSet();

        // 验证坚决未建表
        assertThat(isTableExists("tcc_fence_log")).isFalse();
    }

    @Test
    @DisplayName("支持自定义防卫表名动态自愈创建")
    void shouldSupportCustomTableNameAutoInit() throws Exception {
        String customTable = "my_custom_fence";
        properties.getTcc().setTableName(customTable);
        properties.getTcc().setAutoInitTable(true);

        boolean initialized = initializer.initializeTable();
        assertThat(initialized).isTrue();
        assertThat(isTableExists(customTable)).isTrue();

        // 验证自定义表名 CRUD 正常
        JdbcTccFenceRepository repository = new JdbcTccFenceRepository(dataSource, customTable);
        TccFenceRecord record = new TccFenceRecord("xid-custom-init", 2002L, "customAction", TccFenceStatus.COMMITTED);
        assertThat(repository.insertRecord(record)).isTrue();

        Optional<TccFenceRecord> found = repository.findByXidAndBranchId("xid-custom-init", 2002L);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TccFenceStatus.COMMITTED);
    }

    @Test
    @DisplayName("底层数据库连接异常时转译为受管 TccFenceException")
    void shouldThrowTccFenceExceptionOnSqlError() {
        JdbcDataSource faultyDs = new JdbcDataSource();
        faultyDs.setURL("jdbc:h2:mem:invalid;INIT=FAIL");

        TccFenceTableInitializer faultyInit = new TccFenceTableInitializer(faultyDs, properties);

        assertThatThrownBy(faultyInit::initializeTable)
                .isInstanceOf(TccFenceException.class)
                .hasMessageContaining("TCC 防卫表自愈建表失败");
    }

    private boolean isTableExists(String tableName) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getTables(null, null, tableName.toUpperCase(), new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
            try (ResultSet rs = metaData.getTables(null, null, tableName.toLowerCase(), new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
            return false;
        }
    }
}
