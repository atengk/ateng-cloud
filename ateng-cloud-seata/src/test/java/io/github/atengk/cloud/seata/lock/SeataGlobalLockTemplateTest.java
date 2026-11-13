package io.github.atengk.cloud.seata.lock;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.apache.seata.core.context.GlobalLockConfigHolder;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.exception.TransactionException;
import org.apache.seata.core.exception.TransactionExceptionCode;
import org.apache.seata.core.model.GlobalLockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编程式高并发轻量全局锁执行模板离线单测
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("SeataGlobalLockTemplate 编程式模板单测")
class SeataGlobalLockTemplateTest {

    private SeataProperties properties;
    private SeataGlobalLockTemplate template;

    @BeforeEach
    void setUp() {
        cleanupContext();
        properties = new SeataProperties();
        template = new SeataGlobalLockTemplate(properties);
    }

    @AfterEach
    void tearDown() {
        Thread.interrupted();
        cleanupContext();
    }

    private void cleanupContext() {
        if (RootContext.requireGlobalLock()) {
            RootContext.unbindGlobalLockFlag();
        }
        GlobalLockConfigHolder.remove();
    }

    @Test
    @DisplayName("通过 execute(Callable) 执行业务时自动绑定与解绑全局锁标记")
    void shouldExecuteCallableWithGlobalLock() throws Throwable {
        assertThat(RootContext.requireGlobalLock()).isFalse();

        String result = template.execute(() -> {
            assertThat(RootContext.requireGlobalLock()).isTrue();
            GlobalLockConfig currentConfig = GlobalLockConfigHolder.getCurrentGlobalLockConfig();
            assertThat(currentConfig).isNotNull();
            assertThat(currentConfig.getLockRetryInterval()).isEqualTo(50);
            assertThat(currentConfig.getLockRetryTimes()).isEqualTo(30);
            return "template-success";
        });

        assertThat(result).isEqualTo("template-success");
        assertThat(RootContext.requireGlobalLock()).isFalse();
        assertThat(GlobalLockConfigHolder.getCurrentGlobalLockConfig()).isNull();
    }

    @Test
    @DisplayName("通过 execute(GlobalLockConfig, Callable) 指定自定义锁重试配置")
    void shouldExecuteWithCustomConfig() throws Throwable {
        GlobalLockConfig customConfig = new GlobalLockConfig();
        customConfig.setLockRetryInterval(200);
        customConfig.setLockRetryTimes(10);

        String result = template.execute(customConfig, () -> {
            GlobalLockConfig config = GlobalLockConfigHolder.getCurrentGlobalLockConfig();
            assertThat(config.getLockRetryInterval()).isEqualTo(200);
            assertThat(config.getLockRetryTimes()).isEqualTo(10);
            return "custom-config-success";
        });

        assertThat(result).isEqualTo("custom-config-success");
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @Test
    @DisplayName("通过 executeWithoutResult(Runnable) 正确执行无返回值操作")
    void shouldExecuteWithoutResult() throws Throwable {
        AtomicBoolean executed = new AtomicBoolean(false);

        template.executeWithoutResult(() -> {
            assertThat(RootContext.requireGlobalLock()).isTrue();
            executed.set(true);
        });

        assertThat(executed.get()).isTrue();
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @Test
    @DisplayName("业务执行抛出异常时在 finally 块中确定性解绑并清理全局锁标记")
    void shouldCleanUpWhenCallableThrowsException() {
        assertThatThrownBy(() -> template.execute(() -> {
            assertThat(RootContext.requireGlobalLock()).isTrue();
            throw new RuntimeException("业务逻辑异常");
        })).isInstanceOf(RuntimeException.class).hasMessage("业务逻辑异常");

        assertThat(RootContext.requireGlobalLock()).isFalse();
        assertThat(GlobalLockConfigHolder.getCurrentGlobalLockConfig()).isNull();
    }

    @Test
    @DisplayName("嵌套执行模板逻辑时外层全局锁状态不被提前解绑")
    void shouldHandleNestedTemplateExecution() throws Throwable {
        String result = template.execute(() -> {
            assertThat(RootContext.requireGlobalLock()).isTrue();
            String inner = template.execute(() -> {
                assertThat(RootContext.requireGlobalLock()).isTrue();
                return "inner";
            });
            assertThat(RootContext.requireGlobalLock()).isTrue();
            return inner + "-outer";
        });

        assertThat(result).isEqualTo("inner-outer");
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @Test
    @DisplayName("execute 出口自动转译 TransactionException 为语义化 BusinessException 并保留因果链")
    void shouldTranslateTransactionExceptionOnExecuteExit() {
        TransactionException rootCause = new TransactionException(TransactionExceptionCode.IO, "TC connection refused");
        assertThatThrownBy(() -> template.execute(() -> {
            throw new RuntimeException("tx failed", rootCause);
        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_TC_UNAVAILABLE.getCode());
                    assertThat(be.getCause()).hasCause(rootCause);
                });

        assertThat(RootContext.requireGlobalLock()).isFalse();
        assertThat(GlobalLockConfigHolder.getCurrentGlobalLockConfig()).isNull();
    }

    @Test
    @DisplayName("executeWithRetry 在前 N <= lockRetryTimes 次遭遇锁冲突后退避重试并成功返回")
    void shouldRetryOnLockConflictAndSucceedWithinLimit() throws Throwable {
        GlobalLockConfig config = new GlobalLockConfig();
        config.setLockRetryTimes(3);
        config.setLockRetryInterval(10);

        AtomicInteger attempts = new AtomicInteger(0);
        String result = template.executeWithRetry(config, () -> {
            int current = attempts.incrementAndGet();
            if (current <= 2) {
                throw new TransactionException(TransactionExceptionCode.LockKeyConflict, "Row lock conflict #" + current);
            }
            return "retry-ok-" + current;
        });

        assertThat(result).isEqualTo("retry-ok-3");
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @Test
    @DisplayName("executeWithoutResultWithRetry 支持默认配置与自定义配置退避重试")
    void shouldExecuteWithoutResultWithRetry() throws Throwable {
        properties.getLock().setRetryTimes(2);
        properties.getLock().setRetryInterval(Duration.ofMillis(5));

        AtomicInteger defaultAttempts = new AtomicInteger(0);
        template.executeWithoutResultWithRetry(() -> {
            if (defaultAttempts.incrementAndGet() == 1) {
                throw new BusinessException(SeataResultCode.SEATA_LOCK_CONFLICT, "首次锁竞争冲突");
            }
        });
        assertThat(defaultAttempts.get()).isEqualTo(2);

        GlobalLockConfig customConfig = new GlobalLockConfig();
        customConfig.setLockRetryTimes(1);
        customConfig.setLockRetryInterval(5);
        AtomicInteger customAttempts = new AtomicInteger(0);
        template.executeWithoutResultWithRetry(customConfig, () -> {
            if (customAttempts.incrementAndGet() == 1) {
                throw new BusinessException(SeataResultCode.SEATA_LOCK_CONFLICT, "自定义配置冲突");
            }
        });
        assertThat(customAttempts.get()).isEqualTo(2);
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @Test
    @DisplayName("连续锁冲突超过 lockRetryTimes 时快速失败并保留 5102 因果链")
    void shouldFailFastWhenLockConflictExceedsRetryTimes() {
        GlobalLockConfig config = new GlobalLockConfig();
        config.setLockRetryTimes(2);
        config.setLockRetryInterval(5);

        AtomicInteger attempts = new AtomicInteger(0);
        TransactionException rootCause = new TransactionException(
                TransactionExceptionCode.LockKeyConflictFailFast, "Global lock fast fail");

        assertThatThrownBy(() -> template.executeWithRetry(config, () -> {
            attempts.incrementAndGet();
            throw new RuntimeException("attempt failed", rootCause);
        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_LOCK_CONFLICT.getCode());
                    assertThat(be.getCause()).hasCause(rootCause);
                });

        // 初次尝试 + 2 次重试 = 共 3 次
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @Test
    @DisplayName("遇到非锁冲突业务异常或参数异常时绝不重试，首次即抛出")
    void shouldNotRetryOnNonLockConflictExceptions() {
        GlobalLockConfig config = new GlobalLockConfig();
        config.setLockRetryTimes(3);
        config.setLockRetryInterval(10);

        AtomicInteger businessAttempts = new AtomicInteger(0);
        assertThatThrownBy(() -> template.executeWithRetry(config, () -> {
            businessAttempts.incrementAndGet();
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "参数校验失败");
        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(CommonResultCode.BAD_REQUEST.getCode()));
        assertThat(businessAttempts.get()).isEqualTo(1);

        AtomicInteger illegalArgAttempts = new AtomicInteger(0);
        assertThatThrownBy(() -> template.executeWithRetry(config, () -> {
            illegalArgAttempts.incrementAndGet();
            throw new IllegalArgumentException("非法参数");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(illegalArgAttempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("退避等待期间线程被中断时立即恢复中断状态并抛出 5102 异常")
    void shouldRestoreInterruptFlagAndAbortWhenInterruptedDuringBackoff() {
        GlobalLockConfig config = new GlobalLockConfig();
        config.setLockRetryTimes(3);
        config.setLockRetryInterval(500);

        AtomicInteger attempts = new AtomicInteger(0);
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> template.executeWithRetry(config, () -> {
            attempts.incrementAndGet();
            throw new TransactionException(TransactionExceptionCode.FailedLockGlobalTranscation, "lock conflict");
        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(SeataResultCode.SEATA_LOCK_CONFLICT.getCode()));

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
}
