package io.github.atengk.cloud.dubbo.support;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.dubbo.filter.DubboContextPropagationFilter;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Dubbo 与 Seata 全局事务 XID 跨 RPC 穿透与状态判断对称释放单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class DubboSeataPropagationTest {

    @AfterEach
    void tearDown() {
        if (DubboSeataPropagationSupport.isSeataAvailable()) {
            if (RootContext.inGlobalTransaction()) {
                RootContext.unbind();
            }
        }
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
        RpcContext.removeClientAttachment();
        RpcContext.removeServerAttachment();
        DubboPropertiesHolder.reset();
    }

    @Test
    @DisplayName("消费端出站自动提取当前线程 RootContext 活跃 XID 并注入 Attachment TX_XID")
    void testConsumerExtractsAndInjectsXid() {
        RootContext.bind("192.168.1.100:8091:123456789");

        DubboContextPropagationFilter filter = new DubboContextPropagationFilter();
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        URL consumerUrl = URL.valueOf("tri://127.0.0.1:20880/TestService?side=consumer");
        when(invoker.getUrl()).thenReturn(consumerUrl);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryOrder");

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isNotNull();
        assertThat(invocation.getAttachment(DubboSeataPropagationSupport.TX_XID))
                .isEqualTo("192.168.1.100:8091:123456789");
        assertThat(RpcContext.getClientAttachment().getAttachment(DubboSeataPropagationSupport.TX_XID))
                .isEqualTo("192.168.1.100:8091:123456789");
    }

    @Test
    @DisplayName("消费端当前线程无活跃 XID 时不注入 TX_XID Attachment")
    void testConsumerWithoutXidDoesNotInjectAttachment() {
        DubboContextPropagationFilter filter = new DubboContextPropagationFilter();
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        URL consumerUrl = URL.valueOf("tri://127.0.0.1:20880/TestService?side=consumer");
        when(invoker.getUrl()).thenReturn(consumerUrl);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryOrder");

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isNotNull();
        assertThat(invocation.getAttachment(DubboSeataPropagationSupport.TX_XID)).isNull();
        assertThat(RpcContext.getClientAttachment().getAttachment(DubboSeataPropagationSupport.TX_XID)).isNull();
    }

    @Test
    @DisplayName("服务端入站提取 TX_XID 并成功绑定至 RootContext，在 finally 中对称解绑彻底防泄漏")
    void testProviderExtractsXidAndBindsRootContext() {
        DubboContextPropagationFilter filter = new DubboContextPropagationFilter();
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        URL providerUrl = URL.valueOf("tri://127.0.0.1:20880/TestService?side=provider");
        when(invoker.getUrl()).thenReturn(providerUrl);

        AtomicReference<String> providerObservedXid = new AtomicReference<>();
        when(invoker.invoke(any())).thenAnswer(invocation -> {
            providerObservedXid.set(RootContext.getXID());
            return new AppResponse("ok");
        });

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryOrder");
        invocation.setAttachment(DubboSeataPropagationSupport.TX_XID, "10.0.0.1:8091:987654321");

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isNotNull();
        // 验证执行业务逻辑时已成功绑定事务 XID
        assertThat(providerObservedXid.get()).isEqualTo("10.0.0.1:8091:987654321");
        // 验证离开 filter 后在 finally 块中已对称解绑
        assertThat(RootContext.getXID()).isNull();
    }

    @Test
    @DisplayName("服务端若已存在外部既有 XID，入站冲突跳过绑定，且出口严禁解绑调用方既有上下文")
    void testProviderDeterministicUnbindOnlyWhenBound() {
        String preExistingXid = "127.0.0.1:8091:111111111";
        RootContext.bind(preExistingXid);

        DubboContextPropagationFilter filter = new DubboContextPropagationFilter();
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        URL providerUrl = URL.valueOf("tri://127.0.0.1:20880/TestService?side=provider");
        when(invoker.getUrl()).thenReturn(providerUrl);

        AtomicReference<String> providerObservedXid = new AtomicReference<>();
        when(invoker.invoke(any())).thenAnswer(invocation -> {
            providerObservedXid.set(RootContext.getXID());
            return new AppResponse("ok");
        });

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryOrder");
        // 传入冲突的不同 XID
        invocation.setAttachment(DubboSeataPropagationSupport.TX_XID, "10.0.0.1:8091:222222222");

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isNotNull();
        // 验证未覆盖外部已有的 XID
        assertThat(providerObservedXid.get()).isEqualTo(preExistingXid);
        // 验证出口 finally 未执行 unbind，既有 XID 依然完整保留
        assertThat(RootContext.getXID()).isEqualTo(preExistingXid);
    }

    @Test
    @DisplayName("服务端在抛出异常时依然在 finally 块中确定性对称解绑 XID")
    void testProviderCleansUpContextOnException() {
        DubboContextPropagationFilter filter = new DubboContextPropagationFilter();
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        URL providerUrl = URL.valueOf("tri://127.0.0.1:20880/TestService?side=provider");
        when(invoker.getUrl()).thenReturn(providerUrl);
        when(invoker.invoke(any())).thenThrow(new RpcException("Simulated business failure"));

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryOrder");
        invocation.setAttachment(DubboSeataPropagationSupport.TX_XID, "10.0.0.1:8091:333333333");

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("Simulated business failure");

        // 验证发生异常时 RootContext 依然被安全解绑，无句柄泄露
        assertThat(RootContext.getXID()).isNull();
    }

    @Test
    @DisplayName("验证 DubboSeataPropagationSupport 边缘防御与空安全防卫")
    void testDubboSeataPropagationSupportEdgeCases() {
        assertThat(DubboSeataPropagationSupport.isSeataAvailable()).isTrue();

        // 空值与空白字符串绑定防御
        assertThat(DubboSeataPropagationSupport.bind(null)).isFalse();
        assertThat(DubboSeataPropagationSupport.bind("")).isFalse();
        assertThat(DubboSeataPropagationSupport.bind("   ")).isFalse();
        assertThat(DubboSeataPropagationSupport.bind("null")).isFalse();

        // bound 为 false 时解绑防卫
        DubboSeataPropagationSupport.unbind(false);
        assertThat(RootContext.getXID()).isNull();
    }
}
