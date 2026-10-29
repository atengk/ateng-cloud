package io.github.atengk.cloud.seata.lock;

import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.apache.seata.core.context.GlobalLockConfigHolder;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.model.GlobalLockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

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
}
