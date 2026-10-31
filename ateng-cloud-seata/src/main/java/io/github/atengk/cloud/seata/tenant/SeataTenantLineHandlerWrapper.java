package io.github.atengk.cloud.seata.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import io.github.atengk.cloud.seata.constant.SeataConstants;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.NullValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Seata 多租户内部表自动排除处理器包装器
 * <p>
 * 自动拦截 MyBatis-Plus 行级多租户插件对表名的判定，强制放行 undo_log 与动态配置的 tcc_fence_log 内部系统表，
 * 彻底消除多租户插件自动追加 WHERE tenant_id = ... 导致分布式事务回滚及 TCC 防卫 SQL 解析崩溃的深水区隐患。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SeataTenantLineHandlerWrapper implements TenantLineHandler {

    private static final Logger log = LoggerFactory.getLogger(SeataTenantLineHandlerWrapper.class);

    private static final Set<String> SEATA_INTERNAL_TABLES = Set.of(
            cleanTableName(SeataConstants.DEFAULT_UNDO_LOG_TABLE_NAME),
            cleanTableName(SeataConstants.DEFAULT_TCC_FENCE_TABLE_NAME)
    );

    private final TenantLineHandler delegate;
    private final Set<String> ignoredTables;

    public SeataTenantLineHandlerWrapper(TenantLineHandler delegate) {
        this(delegate, Collections.emptySet());
    }

    public SeataTenantLineHandlerWrapper(TenantLineHandler delegate, String customTccTableName) {
        this(delegate, (customTccTableName != null && !customTccTableName.isBlank())
                ? Set.of(customTccTableName)
                : Collections.emptySet());
    }

    public SeataTenantLineHandlerWrapper(TenantLineHandler delegate, Set<String> additionalIgnoredTables) {
        this.delegate = delegate;
        Set<String> tables = new HashSet<>(SEATA_INTERNAL_TABLES);
        if (additionalIgnoredTables != null) {
            for (String table : additionalIgnoredTables) {
                if (table != null && !table.isBlank()) {
                    tables.add(cleanTableName(table));
                }
            }
        }
        this.ignoredTables = Set.copyOf(tables);
    }

    @Override
    public Expression getTenantId() {
        return delegate != null ? delegate.getTenantId() : new NullValue();
    }

    @Override
    public String getTenantIdColumn() {
        return delegate != null ? delegate.getTenantIdColumn() : "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        if (isIgnoredTable(tableName)) {
            log.trace("MyBatis-Plus 租户插件拦截检测到 Seata 内部表 [{}], 自动放行忽略租户条件注入", tableName);
            return true;
        }
        return delegate != null && delegate.ignoreTable(tableName);
    }

    /**
     * 判断指定表名是否为当前包装器配置排除的内部表
     *
     * @param tableName 待判定表名
     * @return true 表示为排除表，应自动忽略多租户注入
     */
    public boolean isIgnoredTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        return ignoredTables.contains(cleanTableName(tableName));
    }

    public TenantLineHandler getDelegate() {
        return delegate;
    }

    public Set<String> getIgnoredTables() {
        return ignoredTables;
    }

    /**
     * 判断指定表名是否为 Seata 受管默认内部系统表
     *
     * @param tableName 待判定表名
     * @return true 表示为 Seata 默认内部表，应自动忽略多租户注入
     */
    public static boolean isSeataInternalTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        return SEATA_INTERNAL_TABLES.contains(cleanTableName(tableName));
    }

    /**
     * 清洗表名（去除反引号、双引号、首尾空白并转换为小写）
     *
     * @param tableName 原始表名
     * @return 清洗后的规范表名
     */
    public static String cleanTableName(String tableName) {
        if (tableName == null) {
            return "";
        }
        return tableName.replace("`", "")
                .replace("\"", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
