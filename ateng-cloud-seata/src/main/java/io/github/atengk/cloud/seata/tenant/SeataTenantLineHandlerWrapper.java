package io.github.atengk.cloud.seata.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import io.github.atengk.cloud.seata.constant.SeataConstants;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.NullValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

/**
 * Seata 多租户内部表自动排除处理器包装器
 * <p>
 * 自动拦截 MyBatis-Plus 行级多租户插件对表名的判定，硬编码强制放行 undo_log 与 tcc_fence_log 内部系统表，
 * 彻底消除多租户插件自动追加 WHERE tenant_id = ... 导致分布式事务回滚及 TCC 防卫 SQL 解析崩溃的深水区隐患。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SeataTenantLineHandlerWrapper implements TenantLineHandler {

    private static final Logger log = LoggerFactory.getLogger(SeataTenantLineHandlerWrapper.class);

    private static final Set<String> SEATA_INTERNAL_TABLES = Set.of(
            SeataConstants.DEFAULT_UNDO_LOG_TABLE_NAME.toLowerCase(Locale.ROOT),
            SeataConstants.DEFAULT_TCC_FENCE_TABLE_NAME.toLowerCase(Locale.ROOT)
    );

    private final TenantLineHandler delegate;

    public SeataTenantLineHandlerWrapper(TenantLineHandler delegate) {
        this.delegate = delegate;
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
        if (isSeataInternalTable(tableName)) {
            log.trace("MyBatis-Plus 租户插件拦截检测到 Seata 内部表 [{}], 自动放行忽略租户条件注入", tableName);
            return true;
        }
        return delegate != null && delegate.ignoreTable(tableName);
    }

    public TenantLineHandler getDelegate() {
        return delegate;
    }

    /**
     * 判断指定表名是否为 Seata 受管内部系统表
     *
     * @param tableName 待判定表名
     * @return true 表示为 Seata 内部表，应自动忽略多租户注入
     */
    public static boolean isSeataInternalTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        String cleanName = tableName.replace("`", "")
                .replace("\"", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        return SEATA_INTERNAL_TABLES.contains(cleanName);
    }
}
