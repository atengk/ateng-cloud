package io.github.atengk.cloud.dubbo.support;

import io.github.atengk.boot.core.exception.BusinessException;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.support.wrapper.MockClusterInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dubbo 客户端原生 Mock 降级契约白盒单元测试 (User Story 8)
 * <p>
 * 验证消费端配置 mock="true" 时遵循 Dubbo 3 原生同包约定：
 * 1. 当下游服务发生读超时 (TIMEOUT_EXCEPTION) 时，自动平滑触发本地 Mock 降级；
 * 2. 当下游服务抛出未受检系统级异常时，自动触发本地 Mock 降级；
 * 3. 当下游服务正常响应时，直接返回真实业务结果，绝不误触发降级；
 * 4. 当下游抛出受管业务异常 (BIZ_EXCEPTION) 时，原样向上透传，绝不被 Mock 静默吞咽。
 *
 * @author Ateng
 * @since 2026-10-31
 */
class DubboNativeMockFallbackTest {

    @Test
    @DisplayName("当调用超时 (TIMEOUT_EXCEPTION) 时，MockClusterInvoker 自动触发本地同包 Mock 类执行降级")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testFallbackWhenProviderTimeout() {
        URL url = URL.valueOf("tri://127.0.0.1:20880/" + DemoFallbackService.class.getName() + "?mock=true");
        Directory<DemoFallbackService> directory = mock(Directory.class);
        when(directory.getInterface()).thenReturn((Class) DemoFallbackService.class);
        when(directory.getUrl()).thenReturn(url);
        when(directory.getConsumerUrl()).thenReturn(url);
        when(directory.list(any())).thenReturn(Collections.emptyList());

        Invoker<DemoFallbackService> remoteInvoker = mock(Invoker.class);
        when(remoteInvoker.getUrl()).thenReturn(url);
        when(remoteInvoker.getInterface()).thenReturn((Class) DemoFallbackService.class);
        when(remoteInvoker.invoke(any())).thenThrow(new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout after 100ms"));

        MockClusterInvoker<DemoFallbackService> clusterInvoker = new MockClusterInvoker<>(directory, remoteInvoker);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryUser");
        invocation.setParameterTypes(new Class<?>[]{String.class});
        invocation.setArguments(new Object[]{"Ateng"});

        Result result = clusterInvoker.invoke(invocation);
        assertThat(result.hasException()).isFalse();
        assertThat(result.getValue()).isEqualTo("mock-fallback: Ateng");
    }

    @Test
    @DisplayName("当下游抛出系统级未受检异常时，MockClusterInvoker 自动拦截并触发本地 Mock 降级")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testFallbackWhenProviderThrowsUnhandledException() {
        URL url = URL.valueOf("tri://127.0.0.1:20880/" + DemoFallbackService.class.getName() + "?mock=true");
        Directory<DemoFallbackService> directory = mock(Directory.class);
        when(directory.getInterface()).thenReturn((Class) DemoFallbackService.class);
        when(directory.getUrl()).thenReturn(url);
        when(directory.getConsumerUrl()).thenReturn(url);
        when(directory.list(any())).thenReturn(Collections.emptyList());

        Invoker<DemoFallbackService> remoteInvoker = mock(Invoker.class);
        when(remoteInvoker.getUrl()).thenReturn(url);
        when(remoteInvoker.getInterface()).thenReturn((Class) DemoFallbackService.class);
        when(remoteInvoker.invoke(any())).thenThrow(new RpcException("Provider internal database crash"));

        MockClusterInvoker<DemoFallbackService> clusterInvoker = new MockClusterInvoker<>(directory, remoteInvoker);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryUser");
        invocation.setParameterTypes(new Class<?>[]{String.class});
        invocation.setArguments(new Object[]{"Ateng"});

        Result result = clusterInvoker.invoke(invocation);
        assertThat(result.hasException()).isFalse();
        assertThat(result.getValue()).isEqualTo("mock-fallback: Ateng");
    }

    @Test
    @DisplayName("当下游正常响应时，MockClusterInvoker 优先返回真实业务结果")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testLiveProviderSucceedsWhenHealthy() {
        URL url = URL.valueOf("tri://127.0.0.1:20880/" + DemoFallbackService.class.getName() + "?mock=true");
        Directory<DemoFallbackService> directory = mock(Directory.class);
        when(directory.getInterface()).thenReturn((Class) DemoFallbackService.class);
        when(directory.getUrl()).thenReturn(url);
        when(directory.getConsumerUrl()).thenReturn(url);

        Invoker<DemoFallbackService> remoteInvoker = mock(Invoker.class);
        when(remoteInvoker.getUrl()).thenReturn(url);
        when(remoteInvoker.getInterface()).thenReturn((Class) DemoFallbackService.class);
        when(remoteInvoker.invoke(any())).thenReturn(new AppResponse("real-data: Ateng"));

        MockClusterInvoker<DemoFallbackService> clusterInvoker = new MockClusterInvoker<>(directory, remoteInvoker);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryUser");
        invocation.setParameterTypes(new Class<?>[]{String.class});
        invocation.setArguments(new Object[]{"Ateng"});

        Result result = clusterInvoker.invoke(invocation);
        assertThat(result.hasException()).isFalse();
        assertThat(result.getValue()).isEqualTo("real-data: Ateng");
    }

    @Test
    @DisplayName("当下游抛出受管业务异常 (BIZ_EXCEPTION) 时，原样向外抛出，绝不被 Mock 静默降级掩盖")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testBizExceptionNeverMocked() {
        URL url = URL.valueOf("tri://127.0.0.1:20880/" + DemoFallbackService.class.getName() + "?mock=true");
        Directory<DemoFallbackService> directory = mock(Directory.class);
        when(directory.getInterface()).thenReturn((Class) DemoFallbackService.class);
        when(directory.getUrl()).thenReturn(url);
        when(directory.getConsumerUrl()).thenReturn(url);

        Invoker<DemoFallbackService> remoteInvoker = mock(Invoker.class);
        when(remoteInvoker.getUrl()).thenReturn(url);
        when(remoteInvoker.getInterface()).thenReturn((Class) DemoFallbackService.class);
        when(remoteInvoker.invoke(any())).thenThrow(new RpcException(RpcException.BIZ_EXCEPTION, new BusinessException(4001, "用户不存在")));

        MockClusterInvoker<DemoFallbackService> clusterInvoker = new MockClusterInvoker<>(directory, remoteInvoker);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryUser");
        invocation.setParameterTypes(new Class<?>[]{String.class});
        invocation.setArguments(new Object[]{"Ateng"});

        assertThatThrownBy(() -> clusterInvoker.invoke(invocation))
                .isInstanceOf(RpcException.class)
                .satisfies(e -> {
                    RpcException rpcException = (RpcException) e;
                    assertThat(rpcException.isBiz()).isTrue();
                    assertThat(rpcException.getCause()).isInstanceOf(BusinessException.class);
                });
    }

    /**
     * 测试用 RPC 接口
     */
    public interface DemoFallbackService {
        String queryUser(String username);
    }

    /**
     * 测试用同包原生 Mock 降级实现类
     */
    public static class DemoFallbackServiceMock implements DemoFallbackService {
        @Override
        public String queryUser(String username) {
            return "mock-fallback: " + username;
        }
    }
}
