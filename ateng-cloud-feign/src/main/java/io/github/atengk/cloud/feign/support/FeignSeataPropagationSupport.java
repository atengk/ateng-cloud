package io.github.atengk.cloud.feign.support;

import io.github.atengk.cloud.feign.constant.FeignHeaderConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

/**
 * Feign 与 Seata 分布式事务全局 XID 零依赖反射桥接支持类
 * <p>
 * 兼容 Apache Seata ({@code org.apache.seata}) 与历史包名 ({@code io.seata})：
 * 1. 零强依赖：类路径缺失 Seata 时静默回退读取/写入 SLF4J MDC，绝不抛出 ClassNotFoundException；
 * 2. 对称释放保护：入站绑定记录本次绑定标记与入站前 MDC 快照，出口 {@code finally} 块严格按状态对称解绑，防止误清外层既有全局事务。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-12
 */
public final class FeignSeataPropagationSupport {

    private static final Logger log = LoggerFactory.getLogger(FeignSeataPropagationSupport.class);

    private static final boolean SEATA_AVAILABLE;
    private static final Method GET_XID_METHOD;
    private static final Method BIND_METHOD;
    private static final Method UNBIND_METHOD;

    static {
        boolean available = false;
        Method getXid = null;
        Method bind = null;
        Method unbind = null;

        ClassLoader classLoader = FeignSeataPropagationSupport.class.getClassLoader();
        Class<?> rootContextClass = resolveRootContextClass(classLoader);
        if (rootContextClass != null) {
            try {
                getXid = rootContextClass.getMethod("getXID");
                bind = rootContextClass.getMethod("bind", String.class);
                unbind = rootContextClass.getMethod("unbind");
                available = true;
            } catch (Throwable t) {
                log.debug("[FEIGN-SEATA] 初始化 Seata RootContext 反射方法失败，降级为 MDC 模式: {}", t.getMessage());
            }
        }

        SEATA_AVAILABLE = available;
        GET_XID_METHOD = getXid;
        BIND_METHOD = bind;
        UNBIND_METHOD = unbind;
    }

    private FeignSeataPropagationSupport() {
    }

    private static Class<?> resolveRootContextClass(ClassLoader classLoader) {
        String[] candidates = {
                "org.apache.seata.core.context.RootContext",
                "io.seata.core.context.RootContext"
        };
        for (String className : candidates) {
            if (ClassUtils.isPresent(className, classLoader)) {
                try {
                    return ClassUtils.forName(className, classLoader);
                } catch (Throwable t) {
                    log.debug("[FEIGN-SEATA] 加载 {} 失败: {}", className, t.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 判断当前运行环境类路径是否存在可用的 Seata RootContext
     *
     * @return true 表示存在 Seata 环境
     */
    public static boolean isSeataAvailable() {
        return SEATA_AVAILABLE;
    }

    /**
     * 提取当前线程的 Seata 全局事务 XID
     * <p>
     * 优先反射读取 {@code RootContext.getXID()}；当未引入 Seata 或 RootContext 为空时平滑回退读取 MDC。
     * </p>
     *
     * @return 规范化后的 XID 字符串，无活跃事务则返回 null
     */
    public static String getXID() {
        if (SEATA_AVAILABLE && GET_XID_METHOD != null) {
            try {
                String xid = FeignContextHeaderSupport.normalize((String) GET_XID_METHOD.invoke(null));
                if (xid != null) {
                    return xid;
                }
            } catch (Throwable t) {
                log.debug("[FEIGN-SEATA] 读取 RootContext XID 异常: {}", t.getMessage());
            }
        }

        return FeignContextHeaderSupport.firstValidMdcValue(
                FeignHeaderConstants.MDC_XID,
                FeignHeaderConstants.TX_XID
        );
    }

    /**
     * 入站绑定远端传入的全局事务 XID，并返回入站前状态快照
     *
     * @param inboundXid 入站请求头携带的 XID
     * @return 用于 {@code finally} 对称解绑的绑定快照
     */
    public static BindSnapshot bind(String inboundXid) {
        String previousMdcXid = MDC.get(FeignHeaderConstants.MDC_XID);
        String cleanXid = FeignContextHeaderSupport.normalize(inboundXid);
        if (cleanXid == null) {
            return new BindSnapshot(false, previousMdcXid);
        }

        boolean boundRoot = false;
        boolean shouldSetMdc = !SEATA_AVAILABLE;
        if (SEATA_AVAILABLE && GET_XID_METHOD != null && BIND_METHOD != null) {
            try {
                String currentRootXid = FeignContextHeaderSupport.normalize((String) GET_XID_METHOD.invoke(null));
                if (currentRootXid == null) {
                    BIND_METHOD.invoke(null, cleanXid);
                    boundRoot = true;
                    shouldSetMdc = true;
                } else if (cleanXid.equalsIgnoreCase(currentRootXid)) {
                    shouldSetMdc = true;
                } else {
                    log.warn("[FEIGN-SEATA] 入站请求携带 XID [{}] 与当前线程已有 XID [{}] 不一致，跳过覆盖绑定",
                            cleanXid, currentRootXid);
                }
            } catch (Throwable t) {
                log.warn("[FEIGN-SEATA] 绑定 Seata 全局事务 XID 异常: {}", t.getMessage());
            }
        }

        if (shouldSetMdc) {
            MDC.put(FeignHeaderConstants.MDC_XID, cleanXid);
        }
        return new BindSnapshot(boundRoot, previousMdcXid);
    }

    /**
     * 依据入站绑定快照对称解绑 RootContext 并恢复 MDC XID 状态
     *
     * @param snapshot 入站绑定时返回的状态快照
     */
    public static void unbind(BindSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            if (snapshot.boundRootContext() && SEATA_AVAILABLE && UNBIND_METHOD != null) {
                UNBIND_METHOD.invoke(null);
            }
        } catch (Throwable t) {
            log.warn("[FEIGN-SEATA] 对称解绑 Seata RootContext XID 异常: {}", t.getMessage());
        } finally {
            if (snapshot.previousMdcXid() != null) {
                MDC.put(FeignHeaderConstants.MDC_XID, snapshot.previousMdcXid());
            } else {
                MDC.remove(FeignHeaderConstants.MDC_XID);
            }
        }
    }

    /**
     * Seata 全局事务 XID 入站绑定状态快照
     *
     * @param boundRootContext 本次入站是否实际向 RootContext 执行了 bind
     * @param previousMdcXid   入站前 MDC 中原有的 xid 值
     */
    public record BindSnapshot(boolean boundRootContext, String previousMdcXid) {
    }
}
