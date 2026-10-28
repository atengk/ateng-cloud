package io.github.atengk.cloud.nacos.event;

import com.alibaba.nacos.common.remote.client.Connection;
import io.github.atengk.cloud.nacos.metrics.NacosMetricsManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link NacosConnectionEventListener} 单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
class NacosConnectionEventListenerTest {

    @Test
    @DisplayName("长连接建立与断开事件分发及指标联动")
    void testConnectionEventPublishingAndMetrics() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "test-app");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        NacosMetricsManager metricsManager = new NacosMetricsManager(
                registry,
                beanFactory.getBeanProvider(io.github.atengk.cloud.nacos.template.NacosTemplate.class),
                beanFactory.getBeanProvider(io.github.atengk.cloud.nacos.lifecycle.NacosLifecycleStateMachine.class),
                env
        );

        NacosConnectionEventListener listener = new NacosConnectionEventListener(
                "naming-client-test",
                eventPublisher,
                metricsManager
        );

        assertThat(listener.getModuleType()).isEqualTo(NacosConnectionEvent.ModuleType.NAMING);

        Connection connection = mock(Connection.class);
        when(connection.getConnectionId()).thenReturn("192.168.1.100:9848#192.168.1.50:52341");

        // 1. 测试建立连接回调
        listener.onConnected(connection);

        ArgumentCaptor<NacosConnectionEvent> eventCaptor = ArgumentCaptor.forClass(NacosConnectionEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

        NacosConnectionEvent connectedEvent = eventCaptor.getValue();
        assertThat(connectedEvent.isConnected()).isTrue();
        assertThat(connectedEvent.getModuleType()).isEqualTo(NacosConnectionEvent.ModuleType.NAMING);
        assertThat(connectedEvent.getServerAddress()).isEqualTo("192.168.1.100:9848");
        assertThat(connectedEvent.getClientName()).isEqualTo("naming-client-test");

        // 2. 测试断开连接回调
        listener.onDisConnect(connection);

        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        NacosConnectionEvent disconnectedEvent = eventCaptor.getValue();
        assertThat(disconnectedEvent.isDisconnected()).isTrue();
        assertThat(disconnectedEvent.getModuleType()).isEqualTo(NacosConnectionEvent.ModuleType.NAMING);

        // 3. 验证 Micrometer 断连指标累加
        assertThat(metricsManager.getGrpcDisconnectCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("模块类型自动推断与地址解析容错")
    void testModuleTypeAndAddressResolution() {
        assertThat(NacosConnectionEventListener.resolveModuleType("config-client-0"))
                .isEqualTo(NacosConnectionEvent.ModuleType.CONFIG);
        assertThat(NacosConnectionEventListener.resolveModuleType("naming-worker"))
                .isEqualTo(NacosConnectionEvent.ModuleType.NAMING);
        assertThat(NacosConnectionEventListener.resolveModuleType("other-rpc-worker"))
                .isEqualTo(NacosConnectionEvent.ModuleType.UNKNOWN);
        assertThat(NacosConnectionEventListener.resolveModuleType(null))
                .isEqualTo(NacosConnectionEvent.ModuleType.UNKNOWN);

        assertThat(NacosConnectionEventListener.resolveServerAddress(null)).isEqualTo("unknown");

        Connection connNoHash = mock(Connection.class);
        when(connNoHash.getConnectionId()).thenReturn("127.0.0.1:8848");
        assertThat(NacosConnectionEventListener.resolveServerAddress(connNoHash)).isEqualTo("127.0.0.1:8848");
    }

    @Test
    @DisplayName("事件发布异常隔离防御：EventPublisher 异常不影响连接回调")
    void testEventPublishExceptionIsolation() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        doThrow(new RuntimeException("Event publishing error")).when(eventPublisher).publishEvent(any());

        NacosConnectionEventListener listener = new NacosConnectionEventListener(
                "config-client-test",
                eventPublisher,
                null
        );

        Connection connection = mock(Connection.class);
        when(connection.getConnectionId()).thenReturn("127.0.0.1:9848#127.0.0.1:54321");

        // 异常被内部拦截，不向外抛出
        listener.onConnected(connection);
        listener.onDisConnect(connection);
    }

    @Test
    @DisplayName("监听器关闭与生命周期防泄露：close 之后阻断事件广播与指标记录")
    void testCloseBlocksEventPublishing() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        NacosConnectionEventListener listener = new NacosConnectionEventListener(
                "naming-client-test",
                eventPublisher,
                null
        );

        assertThat(listener.isClosed()).isFalse();
        listener.close();
        assertThat(listener.isClosed()).isTrue();

        Connection connection = mock(Connection.class);
        listener.onConnected(connection);
        listener.onDisConnect(connection);

        // 已关闭，绝不发布任何事件
        verify(eventPublisher, never()).publishEvent(any());
    }
}
