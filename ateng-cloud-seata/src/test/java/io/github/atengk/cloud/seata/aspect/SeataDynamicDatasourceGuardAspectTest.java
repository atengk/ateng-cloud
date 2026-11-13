package io.github.atengk.cloud.seata.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.dynamic.datasource.tx.TransactionContext;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import io.github.atengk.cloud.seata.datasource.guard.SeataDynamicDatasourceGuard;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 声明式 @DS 假切源防卫切面单元测试
 *
 * @author Ateng
 * @since 2026-11-13
 */
@DisplayName("SeataDynamicDatasourceGuardAspect 声明式 @DS 防卫切面单测")
class SeataDynamicDatasourceGuardAspectTest {

    private SeataProperties properties;
    private SeataDynamicDatasourceGuard guard;
    private SeataDynamicDatasourceGuardAspect aspect;

    @BeforeEach
    void setUp() {
        cleanupContext();
        properties = new SeataProperties();
        guard = new SeataDynamicDatasourceGuard(properties);
        aspect = new SeataDynamicDatasourceGuardAspect(guard);
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

    interface SwitchService {
        String callSlave();

        @DS("slave_iface")
        String callInterfaceSlave();

        String callSameMaster();

        String callRequiresNewSlave();

        String callClassAnnotated();
    }

    static class SwitchServiceImpl implements SwitchService {

        @Override
        @DS("slave")
        public String callSlave() {
            return "slave-ok";
        }

        @Override
        public String callInterfaceSlave() {
            return "interface-slave-ok";
        }

        @Override
        @DS("master")
        public String callSameMaster() {
            return "master-ok";
        }

        @Override
        @DS("slave")
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public String callRequiresNewSlave() {
            return "requires-new-ok";
        }

        @Override
        public String callClassAnnotated() {
            return "class-ok";
        }
    }

    @DS("slave_class")
    static class ClassLevelSwitchServiceImpl implements SwitchService {

        @Override
        public String callSlave() {
            return "class-slave-ok";
        }

        @Override
        public String callInterfaceSlave() {
            return "interface-slave-ok";
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public String callRequiresNewSlave() {
            return "class-requires-new-ok";
        }

        @Override
        public String callClassAnnotated() {
            return "class-ok";
        }

        @Override
        @DS("master")
        public String callSameMaster() {
            return "class-override-master-ok";
        }

        @DS("   ")
        public String callBlankDs() {
            return "blank-ds-ok";
        }
    }

    private SwitchService createProxy(SwitchService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @Test
    @DisplayName("默认切面优先级为 HIGHEST_PRECEDENCE 以先于切源压栈拦截")
    void shouldDeclareHighestPrecedenceOrder() {
        assertThat(aspect.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("Spring 单连接事务中调用 @DS 跨库方法自动拦截并抛出 5105 异常")
    void shouldBlockFalseSwitchOnAnnotatedMethods() {
        SwitchService proxy = createProxy(new SwitchServiceImpl());
        SwitchService classProxy = createProxy(new ClassLevelSwitchServiceImpl());

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        DynamicDataSourceContextHolder.push("master");

        assertThatThrownBy(proxy::callSlave)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(SeataResultCode.SEATA_FALSE_DATASOURCE_SWITCH.getCode()));

        assertThatThrownBy(classProxy::callInterfaceSlave)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(SeataResultCode.SEATA_FALSE_DATASOURCE_SWITCH.getCode()))
                .hasMessageContaining("slave_iface");

        assertThatThrownBy(classProxy::callClassAnnotated)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(SeataResultCode.SEATA_FALSE_DATASOURCE_SWITCH.getCode()))
                .hasMessageContaining("slave_class");
    }

    @Test
    @DisplayName("全局 XID、@DSTransactional、REQUIRES_NEW 与同源切换均合法放行")
    void shouldAllowLegitimateSwitches() {
        SwitchService proxy = createProxy(new SwitchServiceImpl());
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        DynamicDataSourceContextHolder.push("master");

        // 1. 同源切源放行
        assertThat(proxy.callSameMaster()).isEqualTo("master-ok");

        // 2. REQUIRES_NEW 独立新事务放行
        assertThat(proxy.callRequiresNewSlave()).isEqualTo("requires-new-ok");

        // 3. Seata 全局 XID 激活放行
        RootContext.bind("127.0.0.1:8091:global-tx-xid");
        assertThat(proxy.callSlave()).isEqualTo("slave-ok");
        RootContext.unbind();

        // 4. Baomidou @DSTransactional 激活放行
        TransactionContext.bind("ds-tx-xid");
        assertThat(proxy.callSlave()).isEqualTo("slave-ok");
    }

    @Test
    @DisplayName("failOnFalseSwitch=false 时跨库切换仅输出 WARN 日志并放行")
    void shouldWarnAndAllowWhenFailOnFalseSwitchIsFalse() {
        properties.getDynamicDatasource().setFailOnFalseSwitch(false);
        SwitchService proxy = createProxy(new SwitchServiceImpl());
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        DynamicDataSourceContextHolder.push("master");

        Logger guardLogger = (Logger) LoggerFactory.getLogger(SeataDynamicDatasourceGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);
        try {
            assertThat(proxy.callSlave()).isEqualTo("slave-ok");
            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("slave"));
        } finally {
            guardLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("构造器空校验、空白 @DS 跳过、以及方法级同源 @DS 覆盖类级异源 @DS 均符合预期")
    void shouldHandleGuardBoundaryCases() {
        assertThatThrownBy(() -> new SeataDynamicDatasourceGuardAspect(null))
                .isInstanceOf(NullPointerException.class);

        ClassLevelSwitchServiceImpl target = new ClassLevelSwitchServiceImpl();
        AspectJProxyFactory cglibFactory = new AspectJProxyFactory(target);
        cglibFactory.setProxyTargetClass(true);
        cglibFactory.addAspect(aspect);
        ClassLevelSwitchServiceImpl cglibProxy = cglibFactory.getProxy();

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        DynamicDataSourceContextHolder.push("master");

        // 方法级 @DS("master") 覆盖类级 @DS("slave_class")，判定为同源安全放行
        assertThat(cglibProxy.callSameMaster()).isEqualTo("class-override-master-ok");
        // CGLIB 代理下接口方法 @DS("slave_iface") 覆盖类级 @DS("slave_class")
        assertThatThrownBy(cglibProxy::callInterfaceSlave)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(SeataResultCode.SEATA_FALSE_DATASOURCE_SWITCH.getCode()))
                .hasMessageContaining("slave_iface");
        // 空白 @DS 视为未切源（方法级空白覆盖类级后不触发切源校验）
        assertThat(cglibProxy.callBlankDs()).isEqualTo("blank-ds-ok");
    }
}
