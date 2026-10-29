package io.github.atengk.cloud.seata.aspect;

import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.apache.seata.core.context.GlobalLockConfigHolder;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.model.GlobalLockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 高并发轻量防脏读 @GlobalLock 声明式拦截切面离线单测
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("SeataGlobalLockAspect 声明式切面单测")
class SeataGlobalLockAspectTest {

    private SeataProperties properties;
    private MockGlobalLockService proxyService;

    @BeforeEach
    void setUp() {
        cleanupContext();
        properties = new SeataProperties();
        SeataGlobalLockAspect aspect = new SeataGlobalLockAspect(properties);

        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new MockGlobalLockServiceImpl());
        proxyFactory.addAspect(aspect);
        proxyService = proxyFactory.getProxy();
    }

    @AfterEach
    void tearDown() {
        cleanupContext();
    }

    private void cleanupContext() {
        if (RootContext.requireGlobalLock()) {
            RootContext.unbindGlobalLockFlag();
        }
        GlobalLockConfigHolder.remove();
    }

    @Test
    @DisplayName("标注 org.apache.seata.spring.annotation.GlobalLock 时方法内自动绑定全局锁标记并在结束后解绑")
    void shouldBindAndUnbindGlobalLockFlagWithApacheAnnotation() {
        assertThat(RootContext.requireGlobalLock()).isFalse();

        String result = proxyService.executeWithApacheGlobalLock();

        assertThat(result).isEqualTo("apache-lock-success");
        assertThat(RootContext.requireGlobalLock()).isFalse();
        assertThat(GlobalLockConfigHolder.getCurrentGlobalLockConfig()).isNull();
    }

    @Test
    @DisplayName("标注 io.seata.spring.annotation.GlobalLock 时方法内自动绑定全局锁标记并在结束后解绑")
    void shouldBindAndUnbindGlobalLockFlagWithIoAnnotation() {
        assertThat(RootContext.requireGlobalLock()).isFalse();

        String result = proxyService.executeWithIoGlobalLock();

        assertThat(result).isEqualTo("io-lock-success");
        assertThat(RootContext.requireGlobalLock()).isFalse();
        assertThat(GlobalLockConfigHolder.getCurrentGlobalLockConfig()).isNull();
    }

    @Test
    @DisplayName("方法自定义 lockRetryInterval 与 lockRetryTimes 时正确覆盖默认配置")
    void shouldApplyCustomLockRetryConfig() {
        GlobalLockConfig capturedConfig = proxyService.executeWithCustomConfig();

        assertThat(capturedConfig).isNotNull();
        assertThat(capturedConfig.getLockRetryInterval()).isEqualTo(100);
        assertThat(capturedConfig.getLockRetryTimes()).isEqualTo(50);
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @Test
    @DisplayName("方法未指定自定义重试参数时回退至 SeataProperties.getLock() 生产级默认配置")
    void shouldFallbackToSeataPropertiesDefaultConfig() {
        GlobalLockConfig capturedConfig = proxyService.executeWithDefaultConfig();

        assertThat(capturedConfig).isNotNull();
        assertThat(capturedConfig.getLockRetryInterval()).isEqualTo(50);
        assertThat(capturedConfig.getLockRetryTimes()).isEqualTo(30);
    }

    @Test
    @DisplayName("业务方法抛出异常时在 finally 块中确定性解绑并清理全局锁标记")
    void shouldCleanUpWhenExceptionOccurs() {
        assertThatThrownBy(() -> proxyService.executeWithException())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟全局锁内业务异常");

        assertThat(RootContext.requireGlobalLock()).isFalse();
        assertThat(GlobalLockConfigHolder.getCurrentGlobalLockConfig()).isNull();
    }

    @Test
    @DisplayName("嵌套调用 @GlobalLock 方法时正确维持全局锁上下文且内层结束不提前解绑")
    void shouldHandleNestedGlobalLockCalls() {
        String result = proxyService.executeOuterWithNestedLock();

        assertThat(result).isEqualTo("outer-and-nested-success");
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    @Test
    @DisplayName("未标注 @GlobalLock 的普通方法不绑定全局锁标记")
    void shouldNotBindGlobalLockForNormalMethod() {
        boolean inLock = proxyService.executeNormalMethod();

        assertThat(inLock).isFalse();
        assertThat(RootContext.requireGlobalLock()).isFalse();
    }

    interface MockGlobalLockService {
        String executeWithApacheGlobalLock();
        String executeWithIoGlobalLock();
        GlobalLockConfig executeWithCustomConfig();
        GlobalLockConfig executeWithDefaultConfig();
        void executeWithException();
        String executeOuterWithNestedLock();
        boolean executeNormalMethod();
    }

    static class MockGlobalLockServiceImpl implements MockGlobalLockService {

        @Override
        @org.apache.seata.spring.annotation.GlobalLock
        public String executeWithApacheGlobalLock() {
            if (!RootContext.requireGlobalLock()) {
                throw new IllegalStateException("期望 RootContext.requireGlobalLock() 为 true");
            }
            return "apache-lock-success";
        }

        @Override
        @io.seata.spring.annotation.GlobalLock
        public String executeWithIoGlobalLock() {
            if (!RootContext.requireGlobalLock()) {
                throw new IllegalStateException("期望 RootContext.requireGlobalLock() 为 true");
            }
            return "io-lock-success";
        }

        @Override
        @org.apache.seata.spring.annotation.GlobalLock(lockRetryInterval = 100, lockRetryTimes = 50)
        public GlobalLockConfig executeWithCustomConfig() {
            return GlobalLockConfigHolder.getCurrentGlobalLockConfig();
        }

        @Override
        @org.apache.seata.spring.annotation.GlobalLock
        public GlobalLockConfig executeWithDefaultConfig() {
            return GlobalLockConfigHolder.getCurrentGlobalLockConfig();
        }

        @Override
        @org.apache.seata.spring.annotation.GlobalLock
        public void executeWithException() {
            throw new IllegalStateException("模拟全局锁内业务异常");
        }

        @Override
        @org.apache.seata.spring.annotation.GlobalLock
        public String executeOuterWithNestedLock() {
            if (!RootContext.requireGlobalLock()) {
                throw new IllegalStateException("外层方法期望 requireGlobalLock 为 true");
            }
            executeInner();
            if (!RootContext.requireGlobalLock()) {
                throw new IllegalStateException("内层方法执行完毕后，外层方法期望 requireGlobalLock 仍为 true");
            }
            return "outer-and-nested-success";
        }

        @org.apache.seata.spring.annotation.GlobalLock
        public void executeInner() {
            if (!RootContext.requireGlobalLock()) {
                throw new IllegalStateException("内层方法期望 requireGlobalLock 为 true");
            }
        }

        @Override
        public boolean executeNormalMethod() {
            return RootContext.requireGlobalLock();
        }
    }
}
