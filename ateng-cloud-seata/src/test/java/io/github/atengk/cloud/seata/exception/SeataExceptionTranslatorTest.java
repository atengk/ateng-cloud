package io.github.atengk.cloud.seata.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import org.apache.seata.core.exception.TransactionException;
import org.apache.seata.core.exception.TransactionExceptionCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seata 异常因果转译器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class SeataExceptionTranslatorTest {

    @Test
    @DisplayName("测试受管 BusinessException 原样放行，保留原始错误码与消息")
    void testBusinessExceptionPassthrough() {
        BusinessException original = new BusinessException(CommonResultCode.BAD_REQUEST, "商品库存不足");
        RuntimeException result = SeataExceptionTranslator.translate(original);

        assertThat(result).isSameAs(original);
        assertThat(((BusinessException) result).getCode()).isEqualTo(CommonResultCode.BAD_REQUEST.getCode());
        assertThat(result.getMessage()).isEqualTo("商品库存不足");
    }

    @Test
    @DisplayName("测试全局锁冲突 TransactionException 精确转译为 SEATA_LOCK_CONFLICT")
    void testLockKeyConflictTranslation() {
        TransactionException te = new TransactionException(TransactionExceptionCode.LockKeyConflict, "Row locked by other tx");
        RuntimeException result = SeataExceptionTranslator.translate(te);

        assertThat(result).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) result;
        assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_LOCK_CONFLICT.getCode());
        assertThat(be.getCause()).isSameAs(te);
    }

    @Test
    @DisplayName("测试 TC 网络不可达 TransactionException 精确转译为 SEATA_TC_UNAVAILABLE")
    void testTcUnavailableTranslation() {
        TransactionException te = new TransactionException(TransactionExceptionCode.IO, "Connection refused: 127.0.0.1:8091");
        RuntimeException result = SeataExceptionTranslator.translate(te);

        assertThat(result).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) result;
        assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_TC_UNAVAILABLE.getCode());
        assertThat(be.getCause()).isSameAs(te);
    }

    @Test
    @DisplayName("测试嵌套在其它异常内部的 TransactionException 能被正确检索并转译")
    void testNestedTransactionException() {
        TransactionException te = new TransactionException(TransactionExceptionCode.FailedToSendBranchRollbackRequest, "RPC failed");
        IllegalStateException enclosing = new IllegalStateException("Outer error", te);

        RuntimeException result = SeataExceptionTranslator.translate(enclosing);

        assertThat(result).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) result;
        assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_TC_UNAVAILABLE.getCode());
        assertThat(be.getCause()).isSameAs(enclosing);
    }

    @Test
    @DisplayName("测试受检异常安全转译为受管 BusinessException")
    void testCheckedExceptionWrapping() {
        Exception checkedEx = new Exception("Disk IO failure");
        RuntimeException result = SeataExceptionTranslator.translate(checkedEx);

        assertThat(result).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) result;
        assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_TRANSACTION_FAILED.getCode());
        assertThat(be.getCause()).isSameAs(checkedEx);
    }

    @Test
    @DisplayName("测试 null 入参保底返回默认事务异常")
    void testNullThrowable() {
        RuntimeException result = SeataExceptionTranslator.translate(null);

        assertThat(result).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) result;
        assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_TRANSACTION_FAILED.getCode());
    }
}
