package io.github.atengk.cloud.seata.constant;

/**
 * 分布式事务全域常量类
 *
 * @author Ateng
 * @since 2026-10-29
 */
public final class SeataConstants {

    private SeataConstants() {
    }

    /**
     * Seata 分布式事务属性配置统一前缀
     */
    public static final String PROPERTIES_PREFIX = "ateng.cloud.seata";

    /**
     * 全局事务 XID 标准请求头与 RPC Attachment 键名
     */
    public static final String TX_XID = "TX_XID";

    /**
     * HTTP 请求头中透传的 Seata 全局事务 ID (XID) 键名
     */
    public static final String TX_XID_HEADER = TX_XID;

    /**
     * Seata Web 拦截器默认执行顺序
     */
    public static final int WEB_INTERCEPTOR_ORDER = -100;

    /**
     * TCC 业务防卫日志默认表名
     */
    public static final String DEFAULT_TCC_FENCE_TABLE_NAME = "tcc_fence_log";

    /**
     * Seata AT 模式 undo_log 默认表名
     */
    public static final String DEFAULT_UNDO_LOG_TABLE_NAME = "undo_log";
}
