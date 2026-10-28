package io.github.atengk.cloud.nacos.event;

import com.alibaba.nacos.common.remote.client.ConnectionEventListener;
import com.alibaba.nacos.common.remote.client.RpcClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link NacosConnectionManager} 单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
class NacosConnectionManagerTest {

    @Test
    @DisplayName("手动与自动客户端注册幂等性验证")
    void testRegistrationIdempotence() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();

        NacosConnectionManager manager = new NacosConnectionManager(
                publisher,
                beanFactory.getBeanProvider(io.github.atengk.cloud.nacos.metrics.NacosMetricsManager.class)
        );

        RpcClient client = mock(RpcClient.class);
        when(client.getName()).thenReturn("test-naming-client");

        // 第一次注册应成功
        boolean first = manager.registerToClient(client);
        assertThat(first).isTrue();
        verify(client, times(1)).registerConnectionListener(any(ConnectionEventListener.class));

        // 第二次重复注册相同名称应幂等跳过
        boolean second = manager.registerToClient(client);
        assertThat(second).isFalse();
        verify(client, times(1)).registerConnectionListener(any(ConnectionEventListener.class));

        assertThat(manager.getRegisteredClients()).contains("test-naming-client");

        // null 防御
        assertThat(manager.registerToClient(null)).isFalse();
    }

    @Test
    @DisplayName("生命周期扫描容错：当 RpcClientFactory 为空或异常时不抛错")
    void testLifecycleScanSafe() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();

        NacosConnectionManager manager = new NacosConnectionManager(
                publisher,
                beanFactory.getBeanProvider(io.github.atengk.cloud.nacos.metrics.NacosMetricsManager.class)
        );

        // afterSingletonsInstantiated 不应抛出任何异常
        manager.afterSingletonsInstantiated();
        assertThat(manager.getRegisteredClients()).isNotNull();
    }

    @Test
    @DisplayName("对称注销与销毁清理：注销与 destroy 时关闭监听器并清空集合")
    void testDeregisterAndDestroy() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();

        NacosConnectionManager manager = new NacosConnectionManager(
                publisher,
                beanFactory.getBeanProvider(io.github.atengk.cloud.nacos.metrics.NacosMetricsManager.class)
        );

        RpcClient client1 = mock(RpcClient.class);
        when(client1.getName()).thenReturn("client-1");
        RpcClient client2 = mock(RpcClient.class);
        when(client2.getName()).thenReturn("client-2");

        manager.registerToClient(client1);
        manager.registerToClient(client2);
        assertThat(manager.getRegisteredClients()).containsExactlyInAnyOrder("client-1", "client-2");

        // 1. 注销单客户端
        boolean dereg = manager.deregisterClient("client-1");
        assertThat(dereg).isTrue();
        assertThat(manager.getRegisteredClients()).containsExactly("client-2");
        // 重复注销返回 false
        assertThat(manager.deregisterClient("client-1")).isFalse();
        assertThat(manager.deregisterClient(null)).isFalse();

        // 2. 容器关闭 destroy 清理
        manager.destroy();
        assertThat(manager.getRegisteredClients()).isEmpty();
    }
}
