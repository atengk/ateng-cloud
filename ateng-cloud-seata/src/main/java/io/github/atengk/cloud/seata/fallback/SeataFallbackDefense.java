package io.github.atengk.cloud.seata.fallback;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Seata 分布式事务熔断降级防卫机制 (Seata Fallback Defense)
 * <p>
 * 规约防线：在微服务跨进程声明式调用（如 OpenFeign / Dubbo RPC）中，
 * 若当前调用线程处于活跃全局事务上下文（{@link RootContext#getXID()} 非空），
 * 且下游服务发生熔断降级（由 Sentinel Fallback 或降级工厂捕获），
 * 本防卫机制强制抛出语义化受管业务异常 {@link BusinessException}（错误码 {@link SeataResultCode#SEATA_DEGRADE_BLOCKED}），
 * 坚决杜绝 Fallback 返回静默 Mock 假数据导致外层事务协调切面误判为成功并提交全局事务，
 * 彻底消除单边数据脏写与资损隐患。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public final class SeataFallbackDefense {

    private static final Logger log = LoggerFactory.getLogger(SeataFallbackDefense.class);

    private SeataFallbackDefense() {
    }

    /**
     * 判断当前执行线程是否处于活跃的 Seata 分布式全局事务上下文中
     *
     * @return true 表示存在活跃的全局事务 XID，false 表示当前为无事务普通调用
     */
    public static boolean hasActiveTransaction() {
        return StringUtils.hasText(RootContext.getXID());
    }

    /**
     * 当检测到当前线程处于活跃全局事务中时，强制抛出降级阻断异常
     *
     * @throws BusinessException 当且仅当存在活跃事务时抛出 SEATA_DEGRADE_BLOCKED 异常
     */
    public static void failOnActiveTransaction() {
        failOnActiveTransaction(null, (Throwable) null);
    }

    /**
     * 当检测到当前线程处于活跃全局事务中时，强制抛出包含业务动作描述的降级阻断异常
     *
     * @param actionDesc 下游业务调用动作描述（如 "扣减库存"、"创建订单"）
     * @throws BusinessException 当且仅当存在活跃事务时抛出 SEATA_DEGRADE_BLOCKED 异常
     */
    public static void failOnActiveTransaction(String actionDesc) {
        failOnActiveTransaction(actionDesc, (Throwable) null);
    }

    /**
     * 当检测到当前线程处于活跃全局事务中时，强制抛出保留原始异常因果链的降级阻断异常
     *
     * @param cause 下游触发熔断降级的原始根因（如 BlockException、FeignException 等）
     * @throws BusinessException 当且仅当存在活跃事务时抛出 SEATA_DEGRADE_BLOCKED 异常
     */
    public static void failOnActiveTransaction(Throwable cause) {
        failOnActiveTransaction(null, cause);
    }

    /**
     * 当检测到当前线程处于活跃全局事务中时，强制抛出包含业务描述并保留因果链的降级阻断异常
     *
     * @param actionDesc 下游业务调用动作描述
     * @param cause      下游触发熔断降级的原始根因
     * @throws BusinessException 当且仅当存在活跃事务时抛出 SEATA_DEGRADE_BLOCKED 异常
     */
    public static void failOnActiveTransaction(String actionDesc, Throwable cause) {
        String xid = RootContext.getXID();
        if (StringUtils.hasText(xid)) {
            String message;
            if (StringUtils.hasText(actionDesc)) {
                message = "下游调用 [" + actionDesc + "] 触发熔断降级，全局事务 [" + xid + "] 强制阻断回滚";
            } else {
                message = "下游调用触发熔断降级，全局事务 [" + xid + "] 强制阻断回滚";
            }
            log.warn("[SEATA-FALLBACK-DEFENSE] 触发分布式事务降级防卫，强制阻断并抛出异常回滚全局事务: {}", message);
            if (cause != null) {
                throw new BusinessException(SeataResultCode.SEATA_DEGRADE_BLOCKED, message, cause);
            } else {
                throw new BusinessException(SeataResultCode.SEATA_DEGRADE_BLOCKED, message);
            }
        }
    }
}
