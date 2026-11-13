package io.github.atengk.cloud.seata.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.exception.TransactionException;
import org.apache.seata.core.exception.TransactionExceptionCode;
import org.apache.seata.spring.annotation.GlobalLock;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Seata 异常因果转译切面单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class SeataExceptionTranslationAspectTest {

    interface SampleTransactionalService {
        String executeSuccess();
        void executeWithBusinessException();
        void executeWithSeataLockConflict();
        void executeLockMethod();
    }

    static class SampleTransactionalServiceImpl implements SampleTransactionalService {

        @Override
        @GlobalTransactional
        public String executeSuccess() {
            return "ok";
        }

        @Override
        @GlobalTransactional
        public void executeWithBusinessException() {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "商品库存不足");
        }

        @Override
        @GlobalTransactional
        public void executeWithSeataLockConflict() {
            throw new RuntimeException("wrapped", new TransactionException(TransactionExceptionCode.LockKeyConflict, "Key conflict"));
        }

        @Override
        @GlobalLock
        public void executeLockMethod() {
            throw new RuntimeException("lock failed", new TransactionException(TransactionExceptionCode.FailedLockGlobalTranscation, "Lock failed"));
        }
    }

    @GlobalTransactional
    static class SampleClassLevelTransactionalServiceImpl implements SampleTransactionalService {

        @Override
        public String executeSuccess() {
            return "class-level-ok";
        }

        @Override
        public void executeWithBusinessException() {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "类级别异常");
        }

        @Override
        public void executeWithSeataLockConflict() {
            throw new RuntimeException("wrapped", new TransactionException(TransactionExceptionCode.LockKeyConflict, "Key conflict"));
        }

        @Override
        public void executeLockMethod() {
        }
    }

    private SampleTransactionalService createProxy() {
        SampleTransactionalServiceImpl target = new SampleTransactionalServiceImpl();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new SeataExceptionTranslationAspect());
        return factory.getProxy();
    }

    private SampleTransactionalService createClassLevelProxy() {
        SampleClassLevelTransactionalServiceImpl target = new SampleClassLevelTransactionalServiceImpl();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new SeataExceptionTranslationAspect());
        return factory.getProxy();
    }

    @Test
    @DisplayName("测试全局事务正常方法执行无拦截干扰")
    void testExecuteSuccess() {
        SampleTransactionalService proxy = createProxy();
        assertThat(proxy.executeSuccess()).isEqualTo("ok");
    }

    @Test
    @DisplayName("测试业务受管 BusinessException 原样抛出，保留因果链")
    void testBusinessExceptionPassthrough() {
        SampleTransactionalService proxy = createProxy();
        assertThatThrownBy(proxy::executeWithBusinessException)
                .isInstanceOf(BusinessException.class)
                .hasMessage("商品库存不足")
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(CommonResultCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("测试 Seata 全局锁冲突异常转译为受管 SEATA_LOCK_CONFLICT 业务异常")
    void testSeataLockConflictTranslation() {
        SampleTransactionalService proxy = createProxy();
        assertThatThrownBy(proxy::executeWithSeataLockConflict)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_LOCK_CONFLICT.getCode());
                    assertThat(be.getCause()).isNotNull();
                });
    }

    @Test
    @DisplayName("测试 @GlobalLock 标注的方法异常同样受到切面转译守护")
    void testGlobalLockMethodTranslation() {
        SampleTransactionalService proxy = createProxy();
        assertThatThrownBy(proxy::executeLockMethod)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_LOCK_CONFLICT.getCode());
                });
    }

    @Test
    @DisplayName("测试类级别标注 @GlobalTransactional 时所有方法异常均受切面转译守护")
    void testClassLevelGlobalTransactionalTranslation() {
        SampleTransactionalService proxy = createClassLevelProxy();
        assertThat(proxy.executeSuccess()).isEqualTo("class-level-ok");

        assertThatThrownBy(proxy::executeWithSeataLockConflict)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_LOCK_CONFLICT.getCode());
                });
    }

    @AfterEach
    void cleanupRootContext() {
        RootContext.unbind();
    }

    @Test
    @DisplayName("测试 @GlobalTransactional 方法执行超过 defaultTimeout 时输出结构化 WARN 慢事务告警且不影响返回值")
    void testSlowGlobalTransactionWarnLogging() throws Exception {
        SeataProperties properties = new SeataProperties();
        properties.getTx().setDefaultTimeout(Duration.ofMillis(20));

        SlowTransactionalService target = new SlowTransactionalService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new SeataExceptionTranslationAspect(properties));
        SlowTransactionalService proxy = factory.getProxy();

        Logger aspectLogger = (Logger) LoggerFactory.getLogger(SeataExceptionTranslationAspect.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        aspectLogger.addAppender(appender);
        RootContext.bind("127.0.0.1:8091:slow-tx-test-xid");
        try {
            String okResult = proxy.slowSuccess();
            assertThat(okResult).isEqualTo("slow-ok");

            assertThatThrownBy(proxy::slowFailure)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(SeataResultCode.SEATA_TC_UNAVAILABLE.getCode()));

            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .hasSize(2)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("slowSuccess")
                            .contains("127.0.0.1:8091:slow-tx-test-xid")
                            .contains("20ms"))
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("slowFailure")
                            .contains("127.0.0.1:8091:slow-tx-test-xid"));
        } finally {
            aspectLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("测试未超时、@GlobalLock 方法及非正阈值均不触发慢全局事务 WARN 告警")
    void testNoSlowTransactionWarnWhenWithinTimeoutOrNotGlobalTransactional() {
        SeataProperties properties = new SeataProperties();
        properties.getTx().setDefaultTimeout(Duration.ofSeconds(10));

        SlowTransactionalService target = new SlowTransactionalService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new SeataExceptionTranslationAspect(properties));
        SlowTransactionalService proxy = factory.getProxy();

        Logger aspectLogger = (Logger) LoggerFactory.getLogger(SeataExceptionTranslationAspect.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        aspectLogger.addAppender(appender);
        try {
            assertThat(proxy.fastTransactional()).isEqualTo("fast-ok");
            assertThat(proxy.slowGlobalLock()).isEqualTo("lock-ok");

            SeataProperties zeroTimeoutProps = new SeataProperties();
            zeroTimeoutProps.getTx().setDefaultTimeout(Duration.ZERO);
            AspectJProxyFactory zeroFactory = new AspectJProxyFactory(target);
            zeroFactory.addAspect(new SeataExceptionTranslationAspect(zeroTimeoutProps));
            SlowTransactionalService zeroProxy = zeroFactory.getProxy();
            assertThat(zeroProxy.slowSuccess()).isEqualTo("slow-ok");

            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN).isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            aspectLogger.detachAppender(appender);
            appender.stop();
        }
    }

    static class SlowTransactionalService {

        @GlobalTransactional
        public String slowSuccess() throws InterruptedException {
            Thread.sleep(45);
            return "slow-ok";
        }

        @GlobalTransactional
        public void slowFailure() throws Exception {
            Thread.sleep(45);
            throw new TransactionException(TransactionExceptionCode.IO, "TC timeout");
        }

        @GlobalTransactional
        public String fastTransactional() {
            return "fast-ok";
        }

        @GlobalLock
        public String slowGlobalLock() throws InterruptedException {
            Thread.sleep(25);
            return "lock-ok";
        }
    }
}
