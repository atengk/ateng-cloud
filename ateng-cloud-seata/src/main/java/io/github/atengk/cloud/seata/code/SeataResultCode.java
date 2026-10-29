package io.github.atengk.cloud.seata.code;

import io.github.atengk.boot.core.code.IResultCode;

/**
 * 分布式事务专属响应状态码枚举
 * <p>
 * 编码段规划遵循 ateng-boot-core ResultCodeSegment.PERSISTENCE_TX 规范 (5000 ~ 5999)。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public enum SeataResultCode implements IResultCode {

    /**
     * 分布式全局事务执行失败
     */
    SEATA_TRANSACTION_FAILED(5101, "分布式全局事务执行失败"),

    /**
     * 分布式全局行锁冲突
     */
    SEATA_LOCK_CONFLICT(5102, "分布式全局行锁竞争冲突"),

    /**
     * 分布式事务协调器 (TC) 不可达或连接中断
     */
    SEATA_TC_UNAVAILABLE(5103, "分布式事务协调器 (TC) 不可达或连接中断"),

    /**
     * TCC 分支事务已被业务悬挂拦截
     */
    TCC_FENCE_SUSPENDED(5110, "TCC 分支事务已被业务悬挂拦截"),

    /**
     * TCC 分支事务已被空回滚拦截
     */
    TCC_FENCE_EMPTY_ROLLBACK(5111, "TCC 分支事务已被空回滚拦截"),

    /**
     * TCC 分支事务重复调用已幂等放行
     */
    TCC_FENCE_DUPLICATE_CALL(5112, "TCC 分支事务重复调用已幂等放行");

    private final Integer code;
    private final String msg;

    SeataResultCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public Integer getCode() {
        return this.code;
    }

    @Override
    public String getMsg() {
        return this.msg;
    }
}
