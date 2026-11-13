package io.github.atengk.cloud.seata.datasource.guard;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.dynamic.datasource.tx.TransactionContext;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 协同 Seata 全局分布式事务动态数据源切源守卫离线单测
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("SeataDynamicDatasourceGuard 动态数据源切源守卫单测")
class SeataDynamicDatasourceGuardTest {

    private SeataProperties properties;
    private SeataDynamicDatasourceGuard guard;

    @BeforeEach
    void setUp() {
        cleanupContext();
        properties = new SeataProperties();
        guard = new SeataDynamicDatasourceGuard(properties);
    }

    @AfterEach
    void tearDown() {
        cleanupContext();
    }

    private void cleanupContext() {
        if (RootContext.inGlobalTransaction()) {
            RootContext.unbind();
        }
        TransactionContext.remove();
        DynamicDataSourceContextHolder.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        if (TransactionSynchronizationManager.hasResource(SeataDynamicDatasourceGuard.TX_BOUND_DATASOURCE_RESOURCE_KEY)) {
            TransactionSynchronizationManager.unbindResource(SeataDynamicDatasourceGuard.TX_BOUND_DATASOURCE_RESOURCE_KEY);
        }
    }

    @Test
    @DisplayName("当处于 Seata 全局分布式事务中时合法放行跨库切源操作")
    void shouldAllowSwitchWhenSeataGlobalTransactionActive() {
        RootContext.bind("192.168.1.100:8091:99887766");
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThat(guard.isSeataTransactionActive()).isTrue();
        assertThatCode(() -> guard.validateSwitch("order_ds"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validateContextIsolated("order_ds"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("当处于 Baomidou @DSTransactional 多源协调事务中时放行切源")
    void shouldAllowSwitchWhenDSTransactionActive() {
        TransactionContext.bind("ds-tx-xid-12345");
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThat(guard.isDSTransactionActive()).isTrue();
        assertThatCode(() -> guard.validateSwitch("stock_ds"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("当无 Spring 活跃事务时安全放行切源")
    void shouldAllowSwitchWhenNoTransactionActive() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThat(guard.isSpringTransactionActive()).isFalse();
        assertThatCode(() -> guard.validateSwitch("slave_ds"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Spring 事务中切源至相同数据源时安全放行")
    void shouldAllowSwitchWhenSameDataSourceInSpringTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        DynamicDataSourceContextHolder.push("master");

        assertThatCode(() -> guard.validateSwitch("master"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Spring 单连接事务中跨库切源时刚性拦截并抛出 5105 BusinessException（无重复 error 日志）")
    void shouldFailWhenFalseSwitchDetectedInSpringTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        DynamicDataSourceContextHolder.push("master");

        Logger guardLogger = (Logger) LoggerFactory.getLogger(SeataDynamicDatasourceGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);
        try {
            assertThatThrownBy(() -> guard.validateSwitch("slave_ds"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SeataResultCode.SEATA_FALSE_DATASOURCE_SWITCH.getCode()))
                    .hasMessageContaining("检测到 Spring 单连接事务 (DataSourceTransactionManager) 假切源致命风险")
                    .hasMessageContaining("当前事务已绑定数据源 [master]，尝试切源至 [slave_ds]");

            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.ERROR).isEmpty();
        } finally {
            guardLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("当切源目标方法声明了 REQUIRES_NEW 独立事务时安全放行")
    void shouldAllowSwitchWhenRequiresNewDeclared() throws NoSuchMethodException {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        DynamicDataSourceContextHolder.push("master");

        Method method = MockRequiresNewService.class.getMethod("requiresNewMethod");

        assertThat(guard.isRequiresNewTransaction(method, MockRequiresNewService.class)).isTrue();
        assertThatCode(() -> guard.validateSwitch("slave_ds", method, MockRequiresNewService.class))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("当配置 failOnFalseSwitch=false 时假切源降级为 WARN 告警不抛出异常")
    void shouldDegradeToWarnWhenFailOnFalseSwitchIsFalse() {
        properties.getDynamicDatasource().setFailOnFalseSwitch(false);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        DynamicDataSourceContextHolder.push("master");

        Logger guardLogger = (Logger) LoggerFactory.getLogger(SeataDynamicDatasourceGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);
        try {
            assertThatCode(() -> guard.validateSwitch("slave_ds"))
                    .doesNotThrowAnyException();

            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("failOnFalseSwitch=false"));
        } finally {
            guardLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("当切源守卫被配置禁用时直接跳过拦截")
    void shouldSkipWhenGuardDisabled() {
        properties.getDynamicDatasource().setGuardEnabled(false);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        DynamicDataSourceContextHolder.push("master");

        assertThatCode(() -> guard.validateSwitch("slave_ds"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("当实现类方法未标注但接口方法声明 Propagation.REQUIRES_NEW 时仍应安全放行")
    void shouldAllowSwitchWhenInterfaceDeclaresRequiresNew() throws NoSuchMethodException {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        DynamicDataSourceContextHolder.push("master");

        var implMethod = InterfaceRequiresNewServiceImpl.class.getMethod("requiresNewAction");
        assertThatCode(() -> guard.validateSwitch("slave_ds", implMethod, InterfaceRequiresNewServiceImpl.class))
                .doesNotThrowAnyException();
    }

    interface RequiresNewContract {
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        void requiresNewAction();
    }

    static class InterfaceRequiresNewServiceImpl implements RequiresNewContract {
        @Override
        public void requiresNewAction() {
        }
    }

    static class MockRequiresNewService {
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void requiresNewMethod() {
        }
    }
}
