package io.github.atengk.cloud.seata.tcc;

import io.github.atengk.cloud.seata.code.SeataResultCode;

/**
 * TCC 业务悬挂异常
 * <p>
 * 当 Cancel 操作先于 Try 到达并插入 SUSPENDED 悬挂防卫标记后，
 * 延迟到达的 Try 操作尝试执行时被刚性拦截所抛出的异常。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TccFenceHangingException extends TccFenceException {

    public TccFenceHangingException(String message, String xid, Long branchId) {
        super(SeataResultCode.TCC_FENCE_SUSPENDED, message, xid, branchId);
    }

    public TccFenceHangingException(String message, String xid, Long branchId, Throwable cause) {
        super(SeataResultCode.TCC_FENCE_SUSPENDED, message, xid, branchId, cause);
    }
}
