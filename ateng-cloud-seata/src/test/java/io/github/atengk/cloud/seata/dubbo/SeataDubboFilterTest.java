package io.github.atengk.cloud.seata.dubbo;

import io.github.atengk.cloud.seata.constant.SeataConstants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dubbo 3.3 RPC 全局事务 XID 穿透与自适应幂等防卫过滤器单测
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("SeataDubboFilter Dubbo 事务穿透与幂等防卫测试")
class SeataDubboFilterTest {

    private SeataDubboFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SeataDubboFilter();
        RootContext.unbind();
        RpcContext.removeClientAttachment();
        RpcContext.removeServerAttachment();
        RpcContext.removeContext();
    }

    @AfterEach
    void tearDown() {
        RootContext.unbind();
        RpcContext.removeClientAttachment();
        RpcContext.removeServerAttachment();
        RpcContext.removeContext();
    }

    @Test
    @DisplayName("消费端出站：活跃全局事务自动注入 TX_XID 附件")
    void testConsumerSideInjectsXid() {
        String testXid = "192.168.1.100:8091:123456789";
        RootContext.bind(testXid);

        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = mock(Invoker.class);
        URL consumerUrl = URL.valueOf("tri://127.0.0.1:20880/DemoService?" + CommonConstants.SIDE_KEY + "=" + CommonConstants.CONSUMER_SIDE);
        when(invoker.getUrl()).thenReturn(consumerUrl);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("sayHello");

        Result mockResult = mock(Result.class);
        when(invoker.invoke(invocation)).thenReturn(mockResult);

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isSameAs(mockResult);
        assertThat(invocation.getAttachment(SeataConstants.TX_XID)).isEqualTo(testXid);
        assertThat(RpcContext.getClientAttachment().getAttachment(SeataConstants.TX_XID)).isEqualTo(testXid);
    }

    @Test
    @DisplayName("消费端出站：无全局事务时不注入 TX_XID 附件")
    void testConsumerSideWithoutXid() {
        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = mock(Invoker.class);
        URL consumerUrl = URL.valueOf("tri://127.0.0.1:20880/DemoService?" + CommonConstants.SIDE_KEY + "=" + CommonConstants.CONSUMER_SIDE);
        when(invoker.getUrl()).thenReturn(consumerUrl);

        RpcInvocation invocation = new RpcInvocation();
        Result mockResult = mock(Result.class);
        when(invoker.invoke(invocation)).thenReturn(mockResult);

        filter.invoke(invoker, invocation);

        assertThat(invocation.getAttachment(SeataConstants.TX_XID)).isNull();
        assertThat(RpcContext.getClientAttachment().getAttachment(SeataConstants.TX_XID)).isNull();
    }

    @Test
    @DisplayName("服务端入站：安全绑定 XID 并在出口 finally 块中确定性对称解绑")
    void testProviderSideBindsAndUnbindsXid() {
        String testXid = "192.168.1.100:8091:987654321";

        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = mock(Invoker.class);
        URL providerUrl = URL.valueOf("tri://127.0.0.1:20880/DemoService?" + CommonConstants.SIDE_KEY + "=" + CommonConstants.PROVIDER_SIDE);
        when(invoker.getUrl()).thenReturn(providerUrl);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(SeataConstants.TX_XID, testXid);

        Result mockResult = mock(Result.class);
        when(invoker.invoke(invocation)).thenAnswer(inv -> {
            // 在 invoker 执行期间，断言当前线程已成功绑定 XID
            assertThat(RootContext.getXID()).isEqualTo(testXid);
            return mockResult;
        });

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isSameAs(mockResult);
        // 执行完毕后，出口 finally 必须已确定性解绑
        assertThat(RootContext.getXID()).isNull();
    }

    @Test
    @DisplayName("服务端入站：下游发生异常时出口依然绝对可靠解绑，杜绝线程污染")
    void testProviderSideUnbindsOnException() {
        String testXid = "192.168.1.100:8091:exception-xid";

        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = mock(Invoker.class);
        URL providerUrl = URL.valueOf("tri://127.0.0.1:20880/DemoService?" + CommonConstants.SIDE_KEY + "=" + CommonConstants.PROVIDER_SIDE);
        when(invoker.getUrl()).thenReturn(providerUrl);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(SeataConstants.TX_XID, testXid);

        when(invoker.invoke(invocation)).thenThrow(new RpcException("下游业务故障"));

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("下游业务故障");

        // 异常抛出后必须依然解绑
        assertThat(RootContext.getXID()).isNull();
    }

    @Test
    @DisplayName("自适应幂等防卫：当前线程已被外部过滤器绑定相同时，静默放行且不执行二次解绑")
    void testAdaptiveIdempotencyWhenAlreadyBound() {
        String testXid = "192.168.1.100:8091:idempotent-xid";
        // 模拟外部过滤器（如 DubboContextPropagationFilter）先行完成了 XID 绑定
        RootContext.bind(testXid);

        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = mock(Invoker.class);
        URL providerUrl = URL.valueOf("tri://127.0.0.1:20880/DemoService?" + CommonConstants.SIDE_KEY + "=" + CommonConstants.PROVIDER_SIDE);
        when(invoker.getUrl()).thenReturn(providerUrl);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(SeataConstants.TX_XID, testXid);

        Result mockResult = mock(Result.class);
        when(invoker.invoke(invocation)).thenAnswer(inv -> {
            assertThat(RootContext.getXID()).isEqualTo(testXid);
            return mockResult;
        });

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isSameAs(mockResult);
        // 关键断言：因为本过滤器识别到了外部已绑定，激活自适应幂等防卫，因此出口不执行解绑，外层绑定的 XID 依然有效保留！
        assertThat(RootContext.getXID()).isEqualTo(testXid);
    }

    @Test
    @DisplayName("服务端入站兼容性：支持小写 tx_xid 附件回退提取")
    void testProviderSideSupportsLowercaseTxXid() {
        String testXid = "192.168.1.100:8091:lowercase-xid";

        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = mock(Invoker.class);
        URL providerUrl = URL.valueOf("tri://127.0.0.1:20880/DemoService?" + CommonConstants.SIDE_KEY + "=" + CommonConstants.PROVIDER_SIDE);
        when(invoker.getUrl()).thenReturn(providerUrl);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment("tx_xid", testXid);

        Result mockResult = mock(Result.class);
        when(invoker.invoke(invocation)).thenAnswer(inv -> {
            assertThat(RootContext.getXID()).isEqualTo(testXid);
            return mockResult;
        });

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isSameAs(mockResult);
        assertThat(RootContext.getXID()).isNull();
    }

    @Test
    @DisplayName("本地短路调用优化：injvm 协议直接放行")
    void testInjvmShortCircuit() {
        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = mock(Invoker.class);
        URL injvmUrl = URL.valueOf("injvm://127.0.0.1/DemoService");
        when(invoker.getUrl()).thenReturn(injvmUrl);

        Invocation invocation = mock(Invocation.class);
        Result mockResult = mock(Result.class);
        when(invoker.invoke(invocation)).thenReturn(mockResult);

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isSameAs(mockResult);
        verify(invocation, never()).getAttachment(any());
    }
}
