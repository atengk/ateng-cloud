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
import org.slf4j.MDC;

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
        MDC.remove(SeataConstants.MDC_KEY_XID);
        RpcContext.removeClientAttachment();
        RpcContext.removeServerAttachment();
        RpcContext.removeContext();
    }

    @AfterEach
    void tearDown() {
        RootContext.unbind();
        MDC.remove(SeataConstants.MDC_KEY_XID);
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
    @DisplayName("服务端入站：安全绑定 XID 并在出口 finally 块中确定性对称解绑，全生命周期联动 MDC")
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
            // 在 invoker 执行期间，断言当前线程已成功绑定 XID 与 MDC 黄金关联
            assertThat(RootContext.getXID()).isEqualTo(testXid);
            assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isEqualTo(testXid);
            return mockResult;
        });

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isSameAs(mockResult);
        // 执行完毕后，出口 finally 必须已确定性解绑与清理 MDC
        assertThat(RootContext.getXID()).isNull();
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isNull();
    }

    @Test
    @DisplayName("服务端入站：下游发生异常时出口依然绝对可靠解绑与清理 MDC，杜绝线程污染")
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

        // 异常抛出后必须依然解绑且清理 MDC
        assertThat(RootContext.getXID()).isNull();
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isNull();
    }

    @Test
    @DisplayName("服务端入站：若存在先前 MDC XID，执行完毕后保真恢复原值")
    void testProviderSideRestoresPriorMdcXid() {
        String priorMdcXid = "prior-dubbo-xid-111";
        MDC.put(SeataConstants.MDC_KEY_XID, priorMdcXid);

        String testXid = "192.168.1.100:8091:987654321";

        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = mock(Invoker.class);
        URL providerUrl = URL.valueOf("tri://127.0.0.1:20880/DemoService?" + CommonConstants.SIDE_KEY + "=" + CommonConstants.PROVIDER_SIDE);
        when(invoker.getUrl()).thenReturn(providerUrl);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(SeataConstants.TX_XID, testXid);

        Result mockResult = mock(Result.class);
        when(invoker.invoke(invocation)).thenAnswer(inv -> {
            assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isEqualTo(testXid);
            return mockResult;
        });

        filter.invoke(invoker, invocation);

        assertThat(RootContext.getXID()).isNull();
        // 保真恢复原值
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isEqualTo(priorMdcXid);
    }

    @Test
    @DisplayName("自适应幂等防卫：当前线程已被外部过滤器绑定相同时，静默放行且不执行二次解绑，出口确定性清理 MDC")
    void testAdaptiveIdempotencyWhenAlreadyBound() {
        String testXid = "192.168.1.100:8091:idempotent-xid";
        // 模拟外部过滤器先行完成了 XID 绑定，但未设置 MDC
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
            assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isEqualTo(testXid);
            return mockResult;
        });

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isSameAs(mockResult);
        // 关键断言 1：因为本过滤器识别到了外部已绑定，激活自适应幂等防卫，因此出口不执行解绑，外层绑定的 XID 依然有效保留！
        assertThat(RootContext.getXID()).isEqualTo(testXid);
        // 关键断言 2：出口确定性对称清理本次填充的 MDC，杜绝线程池跨任务脏读
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isNull();
    }

    @Test
    @DisplayName("自适应幂等防卫：外部已有先前 MDC 时，出口保真还原先前 MDC")
    void testAdaptiveIdempotencyPreservesPriorMdcWhenAlreadyBound() {
        String testXid = "192.168.1.100:8091:idempotent-xid";
        String priorMdc = "prior-custom-mdc-xid";
        RootContext.bind(testXid);
        MDC.put(SeataConstants.MDC_KEY_XID, priorMdc);

        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = mock(Invoker.class);
        URL providerUrl = URL.valueOf("tri://127.0.0.1:20880/DemoService?" + CommonConstants.SIDE_KEY + "=" + CommonConstants.PROVIDER_SIDE);
        when(invoker.getUrl()).thenReturn(providerUrl);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(SeataConstants.TX_XID, testXid);

        Result mockResult = mock(Result.class);
        when(invoker.invoke(invocation)).thenAnswer(inv -> {
            assertThat(RootContext.getXID()).isEqualTo(testXid);
            assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isEqualTo(priorMdc);
            return mockResult;
        });

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isSameAs(mockResult);
        assertThat(RootContext.getXID()).isEqualTo(testXid);
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isEqualTo(priorMdc);
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
