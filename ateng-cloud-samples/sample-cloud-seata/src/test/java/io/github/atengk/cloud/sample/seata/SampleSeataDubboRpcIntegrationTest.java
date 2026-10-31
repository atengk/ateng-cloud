package io.github.atengk.cloud.sample.seata;

import io.github.atengk.cloud.seata.constant.SeataConstants;
import io.github.atengk.cloud.seata.dubbo.SeataDubboFilter;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Dubbo 3.3 RPC 跨服务 XID 穿透与自适应幂等防卫集成实测
 * <p>
 * 覆盖：
 * 1. Consumer 消费端：无感提取当前 RootContext XID，透传注入 RPC Attachment (TX_XID)；
 * 2. Provider 服务端：从 RPC Attachment 还原全局事务 XID，绑定 RootContext 并联动 MDC xid；
 * 3. 确定性生命周期：调用结束后在 finally 块中对称释放 RootContext 与 MDC，防止 Netty 线程池污染；
 * 4. 自适应幂等防卫：若外部环境已绑定相同 XID，静默放行并不参与二次解绑，避免截断外层全局事务。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("Dubbo 3.3 RPC 跨微服务 XID 穿透与幂等防卫集成测试")
class SampleSeataDubboRpcIntegrationTest {

    private final SeataDubboFilter dubboFilter = new SeataDubboFilter();

    private static final String TEST_XID = "192.168.1.99:8091:202610319999";

    @BeforeEach
    @AfterEach
    void cleanup() {
        RootContext.unbind();
        MDC.remove(SeataConstants.MDC_KEY_XID);
        RpcContext.removeClientAttachment();
        RpcContext.removeServerAttachment();
        RpcContext.removeContext();
    }

    @Test
    @DisplayName("测试 Dubbo Consumer 消费端出站：活跃事务下自动注入 TX_XID Attachment")
    void shouldPropagateXidOnConsumerSide() {
        RootContext.bind(TEST_XID);

        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = Mockito.mock(Invoker.class);
        URL consumerUrl = URL.valueOf("dubbo://127.0.0.1:20880/io.github.atengk.sample.OrderService?"
                + CommonConstants.SIDE_KEY + "=" + CommonConstants.CONSUMER_SIDE);
        when(invoker.getUrl()).thenReturn(consumerUrl);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryOrder");

        Result result = dubboFilter.invoke(invoker, invocation);

        assertThat(result.getValue()).isEqualTo("ok");
        // 断言 RpcInvocation 与 ClientAttachment 均已成功注入 TX_XID
        assertThat(invocation.getAttachment(SeataConstants.TX_XID)).isEqualTo(TEST_XID);
        assertThat(RpcContext.getClientAttachment().getAttachment(SeataConstants.TX_XID)).isEqualTo(TEST_XID);
    }

    @Test
    @DisplayName("测试 Dubbo Provider 服务端入站：自动还原 XID、绑定 RootContext、同步 MDC 并在出口对称清理")
    void shouldBindAndRestoreOnProviderSide() {
        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = Mockito.mock(Invoker.class);
        URL providerUrl = URL.valueOf("dubbo://127.0.0.1:20880/io.github.atengk.sample.OrderService?"
                + CommonConstants.SIDE_KEY + "=" + CommonConstants.PROVIDER_SIDE);
        when(invoker.getUrl()).thenReturn(providerUrl);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("executeTcc");
        invocation.setAttachment(SeataConstants.TX_XID, TEST_XID);

        AtomicReference<String> capturedRootContextXid = new AtomicReference<>();
        AtomicReference<String> capturedMdcXid = new AtomicReference<>();

        when(invoker.invoke(any())).thenAnswer(invocationOnMock -> {
            capturedRootContextXid.set(RootContext.getXID());
            capturedMdcXid.set(MDC.get(SeataConstants.MDC_KEY_XID));
            return new AppResponse("tcc-done");
        });

        Result result = dubboFilter.invoke(invoker, invocation);

        assertThat(result.getValue()).isEqualTo("tcc-done");
        // 关键断言：业务执行期间成功绑定并联动 MDC
        assertThat(capturedRootContextXid.get()).isEqualTo(TEST_XID);
        assertThat(capturedMdcXid.get()).isEqualTo(TEST_XID);

        // 关键断言：调用退出后对称释放，零线程污染
        assertThat(RootContext.getXID()).isNull();
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isNull();
    }

    @Test
    @DisplayName("测试 Dubbo Provider 自适应幂等防卫：外部已绑定相同 XID 时静默放行且不触发二次解绑")
    void shouldTriggerAdaptiveIdempotencyWhenAlreadyBound() {
        // 模拟外部切面或 Filter 已先期绑定相同 XID
        RootContext.bind(TEST_XID);
        MDC.put(SeataConstants.MDC_KEY_XID, TEST_XID);

        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = Mockito.mock(Invoker.class);
        URL providerUrl = URL.valueOf("dubbo://127.0.0.1:20880/io.github.atengk.sample.OrderService?"
                + CommonConstants.SIDE_KEY + "=" + CommonConstants.PROVIDER_SIDE);
        when(invoker.getUrl()).thenReturn(providerUrl);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("nestedCall");
        invocation.setAttachment(SeataConstants.TX_XID, TEST_XID);

        when(invoker.invoke(any())).thenReturn(new AppResponse("nested-ok"));

        Result result = dubboFilter.invoke(invoker, invocation);

        assertThat(result.getValue()).isEqualTo("nested-ok");
        // 关键断言：自适应防卫生效，外部已有的事务上下文未被提前误解绑！
        assertThat(RootContext.getXID()).isEqualTo(TEST_XID);
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isEqualTo(TEST_XID);
    }

    @Test
    @DisplayName("测试 injvm 进程内短路调用：跳过拦截处理")
    void shouldBypassInjvmProtocol() {
        RootContext.bind(TEST_XID);

        @SuppressWarnings("unchecked")
        Invoker<Object> invoker = Mockito.mock(Invoker.class);
        URL injvmUrl = URL.valueOf("injvm://127.0.0.1/io.github.atengk.sample.OrderService");
        when(invoker.getUrl()).thenReturn(injvmUrl);
        when(invoker.invoke(any())).thenReturn(new AppResponse("injvm-ok"));

        RpcInvocation invocation = new RpcInvocation();
        Result result = dubboFilter.invoke(invoker, invocation);

        assertThat(result.getValue()).isEqualTo("injvm-ok");
        assertThat(invocation.getAttachment(SeataConstants.TX_XID)).isNull();
    }
}
