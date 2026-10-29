package io.github.atengk.cloud.seata.tcc;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.seata.code.SeataResultCode;

/**
 * TCC Fence 业务防卫屏障基础异常
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TccFenceException extends BusinessException {

    private final String xid;
    private final Long branchId;

    public TccFenceException(String message) {
        this(SeataResultCode.TCC_FENCE_STATE_ERROR, message, null, null, null);
    }

    public TccFenceException(String message, String xid, Long branchId) {
        this(SeataResultCode.TCC_FENCE_STATE_ERROR, message, xid, branchId, null);
    }

    public TccFenceException(String message, String xid, Long branchId, Throwable cause) {
        this(SeataResultCode.TCC_FENCE_STATE_ERROR, message, xid, branchId, cause);
    }

    public TccFenceException(IResultCode resultCode, String message) {
        this(resultCode, message, null, null, null);
    }

    public TccFenceException(IResultCode resultCode, String message, String xid, Long branchId) {
        this(resultCode, message, xid, branchId, null);
    }

    public TccFenceException(IResultCode resultCode, String message, String xid, Long branchId, Throwable cause) {
        super(resultCode, message, cause);
        this.xid = xid;
        this.branchId = branchId;
    }

    public String getXid() {
        return xid;
    }

    public Long getBranchId() {
        return branchId;
    }
}
