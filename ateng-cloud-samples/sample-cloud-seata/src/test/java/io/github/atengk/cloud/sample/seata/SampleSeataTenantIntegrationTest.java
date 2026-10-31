package io.github.atengk.cloud.sample.seata;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import io.github.atengk.cloud.seata.tenant.SeataTenantLineHandlerWrapper;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis-Plus 多租户行级隔离插件与 Seata 内部表动态放行端到端集成实测
 * <p>
 * 覆盖：
 * 1. 动态自定义表名放行：支持将非标准 TCC 防卫表（如 custom_fence_log）及 undo_log 自动加入白名单；
 * 2. 大小写与反引号弹性防御：支持 `CUSTOM_FENCE_LOG` 等方言格式清洗；
 * 3. 真实 SQL 重写校验：通过 MyBatis-Plus TenantLineInnerInterceptor 校验业务表注入租户条件，
 *    而 Seata 内部表彻底免疫租户过滤，防止 SQL 解析崩溃与事务死锁。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("MyBatis-Plus 多租户行级隔离与自定义防卫表放行集成测试")
class SampleSeataTenantIntegrationTest {

    private static final String CUSTOM_FENCE_TABLE = "sample_custom_tcc_fence";
    private static final String TENANT_VALUE = "tenant_company_001";

    @Test
    @DisplayName("测试自定义表名与默认系统表的放行判定")
    void shouldIgnoreSeataInternalAndCustomTables() {
        TenantLineHandler userHandler = new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue(TENANT_VALUE);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // 模拟业务定义：默认所有表都不放行
                return false;
            }
        };

        SeataTenantLineHandlerWrapper wrapper = new SeataTenantLineHandlerWrapper(userHandler, CUSTOM_FENCE_TABLE);

        // 1. 业务表不得被放行（保持租户过滤）
        assertThat(wrapper.ignoreTable("order_tbl")).isFalse();
        assertThat(wrapper.ignoreTable("account_tbl")).isFalse();

        // 2. Seata 默认内部表必须自动放行
        assertThat(wrapper.ignoreTable("undo_log")).isTrue();
        assertThat(wrapper.ignoreTable("tcc_fence_log")).isTrue();
        assertThat(wrapper.ignoreTable("UNDO_LOG")).isTrue();
        assertThat(wrapper.ignoreTable("`undo_log`")).isTrue();

        // 3. 动态自定义 TCC 表名必须自动放行（含大小写与反引号）
        assertThat(wrapper.ignoreTable(CUSTOM_FENCE_TABLE)).isTrue();
        assertThat(wrapper.ignoreTable("SAMPLE_CUSTOM_TCC_FENCE")).isTrue();
        assertThat(wrapper.ignoreTable("`sample_custom_tcc_fence`")).isTrue();
        assertThat(wrapper.ignoreTable("\"sample_custom_tcc_fence\"")).isTrue();
    }

    @Test
    @DisplayName("测试真实 SQL 重写器：业务表注入租户条件，Seata 内部表零污染保持原样")
    void shouldRewriteSqlSelectAccurately() {
        TenantLineHandler userHandler = new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue(TENANT_VALUE);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return false;
            }
        };

        SeataTenantLineHandlerWrapper wrapper = new SeataTenantLineHandlerWrapper(userHandler, CUSTOM_FENCE_TABLE);
        TenantLineInnerInterceptor tenantInterceptor = new TenantLineInnerInterceptor(wrapper);

        // 1. 业务表查询：必须自动追加 WHERE tenant_id = 'tenant_company_001'
        String orderSql = "SELECT * FROM order_tbl WHERE id = 1";
        String rewrittenOrderSql = tenantInterceptor.parserSingle(orderSql, null);
        assertThat(rewrittenOrderSql).contains("tenant_id = '" + TENANT_VALUE + "'");

        // 2. 默认 undo_log 表查询：严禁追加租户条件
        String undoLogSql = "SELECT * FROM undo_log WHERE xid = '127.0.0.1:8091:1001'";
        String rewrittenUndoSql = tenantInterceptor.parserSingle(undoLogSql, null);
        assertThat(rewrittenUndoSql).doesNotContain("tenant_id");
        assertThat(rewrittenUndoSql).contains("WHERE xid = '127.0.0.1:8091:1001'");

        // 3. 动态配置的自定义 TCC 防卫表：严禁追加租户条件，防御 SQL 崩溃
        String customFenceSql = "SELECT * FROM sample_custom_tcc_fence WHERE xid = '127.0.0.1:8091:1002' AND branch_id = 99";
        String rewrittenCustomSql = tenantInterceptor.parserSingle(customFenceSql, null);
        assertThat(rewrittenCustomSql).doesNotContain("tenant_id");
        assertThat(rewrittenCustomSql).contains("sample_custom_tcc_fence");
    }
}
