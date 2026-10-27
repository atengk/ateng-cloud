package io.github.atengk.cloud.nacos.spi;

import io.github.atengk.cloud.nacos.event.SafeConfigRefreshEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NacosConfigChangeListener} SPI 默认行为测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class NacosConfigChangeListenerTest {

    @Test
    @DisplayName("SPI 默认方法契约：默认前缀为空字符串（监听全部），默认排序优先级为 0")
    void testDefaultInterfaceMethods() {
        AtomicBoolean called = new AtomicBoolean(false);
        NacosConfigChangeListener listener = event -> called.set(true);

        assertThat(listener.getPrefix()).isEmpty();
        assertThat(listener.getOrder()).isEqualTo(0);

        SafeConfigRefreshEvent event = new SafeConfigRefreshEvent(this, null, null, null, null, null);
        listener.onConfigChange(event);
        assertThat(called.get()).isTrue();
    }
}
