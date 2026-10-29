package io.github.atengk.cloud.seata.exception;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import org.apache.seata.core.exception.TransactionException;
import org.apache.seata.core.exception.TransactionExceptionCode;

/**
 * Seata 底层驱动异常因果转译器
 * <p>
 * 拦截 Seata 客户端在网络通信、锁竞争与分支协调过程中抛出的底层 TransactionException 驱动异常，
 * 将其解耦转译为框架统一受管的语义化 BusinessException，并保留完整的原始异常因果链。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public final class SeataExceptionTranslator {

    private SeataExceptionTranslator() {
    }

    /**
     * 将任意异常受控转译为框架受管的 RuntimeException
     *
     * @param throwable 捕获的异常对象
     * @return 转译后的异常（若已为 BusinessException 则原样保留，若为 Seata 异常则转换为语义化 BusinessException）
     */
    public static RuntimeException translate(Throwable throwable) {
        if (throwable == null) {
            return new BusinessException(SeataResultCode.SEATA_TRANSACTION_FAILED, "未知分布式事务异常");
        }

        // 1. 若已经是受管 BusinessException，直接原样放行，保留原始错误码与因果链
        if (throwable instanceof BusinessException be) {
            return be;
        }

        // 2. 检查当前异常或根因是否为 Seata TransactionException
        TransactionException te = findTransactionException(throwable);
        if (te != null) {
            return translateTransactionException(te, throwable);
        }

        // 3. 其它运行时异常原样抛出
        if (throwable instanceof RuntimeException re) {
            return re;
        }

        // 4. 受检异常统一包装为受管 BusinessException
        return new BusinessException(SeataResultCode.SEATA_TRANSACTION_FAILED, throwable.getMessage(), throwable);
    }

    /**
     * 精确转译 Seata TransactionException
     *
     * @param te              Seata 事务异常对象
     * @param rootOrEnclosing 外层抛出的包包含异常
     * @return 语义化 BusinessException
     */
    private static BusinessException translateTransactionException(TransactionException te, Throwable rootOrEnclosing) {
        TransactionExceptionCode code = te.getCode();
        Throwable cause = (rootOrEnclosing != null) ? rootOrEnclosing : te;

        if (code == TransactionExceptionCode.LockKeyConflict
                || code == TransactionExceptionCode.LockKeyConflictFailFast
                || code == TransactionExceptionCode.FailedLockGlobalTranscation) {
            return new BusinessException(SeataResultCode.SEATA_LOCK_CONFLICT, "分布式事务全局行锁冲突: " + te.getMessage(), cause);
        }

        if (code == TransactionExceptionCode.IO
                || code == TransactionExceptionCode.FailedToSendBranchCommitRequest
                || code == TransactionExceptionCode.FailedToSendBranchRollbackRequest
                || code == TransactionExceptionCode.NotRaftLeader) {
            return new BusinessException(SeataResultCode.SEATA_TC_UNAVAILABLE, "分布式事务协调器 (TC) 连接异常: " + te.getMessage(), cause);
        }

        return new BusinessException(SeataResultCode.SEATA_TRANSACTION_FAILED, "分布式全局事务执行失败: " + te.getMessage(), cause);
    }

    /**
     * 在异常调用链中递归检索 TransactionException
     *
     * @param throwable 异常
     * @return 匹配到的 TransactionException，若未找到则返回 null
     */
    public static TransactionException findTransactionException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TransactionException te) {
                return te;
            }
            current = current.getCause();
        }
        return null;
    }
}
