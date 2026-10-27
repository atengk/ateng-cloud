package io.github.atengk.cloud.nacos.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NacosConnectionEvent} 单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
class NacosConnectionEventTest {

    @Test
    @DisplayName("领域事件基本属性与状态判断契约")
    void testEventContract() {
        NacosConnectionEvent connectedEvent = new NacosConnectionEvent(
                this,
                NacosConnectionEvent.ModuleType.NAMING,
                NacosConnectionEvent.ConnectionStatus.CONNECTED,
                "127.0.0.1:9848",
                "naming-client-1"
        );

        assertThat(connectedEvent.getModuleType()).isEqualTo(NacosConnectionEvent.ModuleType.NAMING);
        assertThat(connectedEvent.getStatus()).isEqualTo(NacosConnectionEvent.ConnectionStatus.CONNECTED);
        assertThat(connectedEvent.isConnected()).isTrue();
        assertThat(connectedEvent.isDisconnected()).isFalse();
        assertThat(connectedEvent.getServerAddress()).isEqualTo("127.0.0.1:9848");
        assertThat(connectedEvent.getClientName()).isEqualTo("naming-client-1");
        assertThat(connectedEvent.getTimestamp()).isPositive();
        assertThat(connectedEvent.toString()).contains("NAMING", "CONNECTED", "127.0.0.1:9848");

        NacosConnectionEvent disconnectedEvent = new NacosConnectionEvent(
                this,
                NacosConnectionEvent.ModuleType.CONFIG,
                NacosConnectionEvent.ConnectionStatus.DISCONNECTED,
                "127.0.0.1:9848",
                "config-client-1"
        );

        assertThat(disconnectedEvent.getModuleType()).isEqualTo(NacosConnectionEvent.ModuleType.CONFIG);
        assertThat(disconnectedEvent.getStatus()).isEqualTo(NacosConnectionEvent.ConnectionStatus.DISCONNECTED);
        assertThat(disconnectedEvent.isConnected()).isFalse();
        assertThat(disconnectedEvent.isDisconnected()).isTrue();
    }

    @Test
    @DisplayName("空值入参防御保底")
    void testNullSafety() {
        NacosConnectionEvent event = new NacosConnectionEvent(this, null, null, null, null);

        assertThat(event.getModuleType()).isEqualTo(NacosConnectionEvent.ModuleType.UNKNOWN);
        assertThat(event.getStatus()).isEqualTo(NacosConnectionEvent.ConnectionStatus.DISCONNECTED);
        assertThat(event.getServerAddress()).isEqualTo("unknown");
        assertThat(event.getClientName()).isEqualTo("unknown");
    }
}
