package io.github.atengk.cloud.dubbo.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * Dubbo 与 Seata 分布式事务全局 XID 透传平滑适配桥梁
 * <p>
 * 基于反射与 ClassUtils 条件探测，提供对 Seata RootContext 的解耦支持：
 * 1. 零强依赖：类路径缺失 Seata 时自动静默短路，不抛出 ClassNotFoundException；
 * 2. 状态对称保护：提供严格的局部状态判定绑定与释放语义，仅当本端真正绑定 XID 时才在出口执行 unbind；
 * 3. 线程安全与因果保真：防御线程池复用污染与异常覆盖。
 *
 * @author Ateng
 * @since 2026-10-31
 */
public final class DubboSeataPropagationSupport {

    private static final Logger log = LoggerFactory.getLogger(DubboSeataPropagationSupport.class);

    /**
     * Seata 全局分布式事务 XID 标准请求头与 RPC Attachment 键名
     */
    public static final String TX_XID = "TX_XID";

    /**
     * 小写兼容性键名
     */
    public static final String TX_XID_LOWERCASE = "tx_xid";

    private static final boolean SEATA_AVAILABLE;
    private static final Method GET_XID_METHOD;
    private static final Method BIND_METHOD;
    private static final Method UNBIND_METHOD;

    static {
        boolean available = false;
        Method getXid = null;
        Method bind = null;
        Method unbind = null;

        ClassLoader classLoader = DubboSeataPropagationSupport.class.getClassLoader();
        Class<?> rootContextClass = null;
        if (ClassUtils.isPresent("org.apache.seata.core.context.RootContext", classLoader)) {
            try {
                rootContextClass = ClassUtils.forName("org.apache.seata.core.context.RootContext", classLoader);
            } catch (Throwable t) {
                log.debug("[DUBBO-SEATA] 加载 org.apache.seata.core.context.RootContext 失败: {}", t.getMessage());
            }
        } else if (ClassUtils.isPresent("io.seata.core.context.RootContext", classLoader)) {
            try {
                rootContextClass = ClassUtils.forName("io.seata.core.context.RootContext", classLoader);
            } catch (Throwable t) {
                log.debug("[DUBBO-SEATA] 加载 io.seata.core.context.RootContext 失败: {}", t.getMessage());
            }
        }

        if (rootContextClass != null) {
            try {
                getXid = rootContextClass.getMethod("getXID");
                bind = rootContextClass.getMethod("bind", String.class);
                unbind = rootContextClass.getMethod("unbind");
                available = true;
            } catch (Throwable t) {
                log.debug("[DUBBO-SEATA] 初始化 Seata RootContext 反射方法失败，降级为不可用: {}", t.getMessage());
            }
        }

        SEATA_AVAILABLE = available;
        GET_XID_METHOD = getXid;
        BIND_METHOD = bind;
        UNBIND_METHOD = unbind;
    }

    private DubboSeataPropagationSupport() {
    }

    /**
     * 判断当前运行环境类路径中是否存在可用的 Seata RootContext
     *
     * @return true 表示存在可用 Seata 环境，false 表示不存在
     */
    public static boolean isSeataAvailable() {
        return SEATA_AVAILABLE;
    }

    /**
     * 提取当前线程绑定的 Seata 全局事务 ID (XID)
     *
     * @return 当前事务 XID，若无事务或 Seata 未引入则返回 null
     */
    public static String getXID() {
        if (!SEATA_AVAILABLE || GET_XID_METHOD == null) {
            return null;
        }
        try {
            return (String) GET_XID_METHOD.invoke(null);
        } catch (Throwable t) {
            log.debug("[DUBBO-SEATA] 读取当前线程 Seata XID 异常: {}", t.getMessage());
            return null;
        }
    }

    /**
     * 将远端 RPC 传入的全局事务 XID 绑定至当前线程 RootContext
     * <p>
     * 仅当当前线程尚未绑定 XID 时才执行绑定并返回 true；
     * 若当前线程已存在相同 XID 或绑定失败则返回 false，防止次生重复解绑。
     *
     * @param rpcXid 远端透传的全局事务 XID
     * @return true 表示本次调用成功绑定了 XID（调用方在 finally 中必须执行对称 unbind），false 表示未执行绑定
     */
    public static boolean bind(String rpcXid) {
        if (!SEATA_AVAILABLE || BIND_METHOD == null || !StringUtils.hasText(rpcXid)
                || "null".equalsIgnoreCase(rpcXid.trim())) {
            return false;
        }
        String trimmedXid = rpcXid.trim();
        try {
            String currentXid = getXID();
            if (currentXid == null) {
                BIND_METHOD.invoke(null, trimmedXid);
                MDC.put("xid", trimmedXid);
                if (log.isDebugEnabled()) {
                    log.debug("[DUBBO-SEATA] 成功为当前线程绑定全局事务 XID 与 MDC 黄金关联: [{}]", trimmedXid);
                }
                return true;
            } else if (!trimmedXid.equalsIgnoreCase(currentXid)) {
                log.warn("[DUBBO-SEATA] 入站请求携带 XID [{}] 与当前线程已绑定 XID [{}] 冲突，跳过重新绑定",
                        trimmedXid, currentXid);
            }
        } catch (Throwable t) {
            log.warn("[DUBBO-SEATA] 绑定 Seata 全局事务 XID 异常: {}", t.getMessage());
        }
        return false;
    }

    /**
     * 依据绑定状态执行对称解绑
     * <p>
     * 遵循确定性生命周期与对称释放防线：当且仅当入站时由本过滤器实际绑定了 XID（bound 为 true）时，
     * 才在出口 finally 触发 unbind；未绑定时严禁盲目释放外层既有上下文。
     *
     * @param bound 当前线程是否在本次入站中成功执行了 bind
     */
    public static void unbind(boolean bound) {
        if (!bound || !SEATA_AVAILABLE || UNBIND_METHOD == null) {
            return;
        }
        try {
            UNBIND_METHOD.invoke(null);
            if (log.isDebugEnabled()) {
                log.debug("[DUBBO-SEATA] 成功对称解绑当前线程全局事务 XID");
            }
        } catch (Throwable t) {
            log.warn("[DUBBO-SEATA] 对称解绑 Seata 全局事务 XID 异常: {}", t.getMessage());
        } finally {
            MDC.remove("xid");
        }
    }
}
