package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import io.github.atengk.cloud.dubbo.support.DubboSeataPropagationSupport;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invocation;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dubbo 全链路四维上下文、Seata XID 与流量染色透传过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class DubboContextPropagationFilterTest {

    private final DubboContextPropagationFilter filter = new DubboContextPropagationFilter();

    @BeforeEach
    void setUp() {
        DubboPropertiesHolder.set(new AtengDubboProperties());
        cleanAllContexts();
    }

    @AfterEach
    void tearDown() {
        DubboPropertiesHolder.set(null);
        cleanAllContexts();
    }

    private void cleanAllContexts() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        TrafficContextHolder.clear();
        MDC.clear();
        RpcContext.removeClientAttachment();
        RpcContext.removeServerAttachment();
        DubboSeataPropagationSupport.unbind(true);
    }

    @Test
    @DisplayName("消费端出站: 透明捕获本地四维上下文、Seata XID 与流量染色注入 RPC 附件")
    void testConsumerOutboundPropagation() {
        // 1. 本地线程模拟注入上下文
        MDC.put(MessageHeaderConstants.TRACE_ID, "trace-client-12345");
        UserContextHolder.setUserId("user-8888");
        TenantContextHolder.setTenantId("tenant-666");
        TenantContextHolder.setIgnoreTenant(true);
        TrafficContextHolder.setContext(TrafficContext.of("gray-canary-v2"));
        RootContext.bind("192.168.1.100:8091:202610310001");

        // 2. 构造消费端 Invoker 与 Invocation
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer");
        when(invoker.getUrl()).thenReturn(url);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryUser");

        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        // 3. 执行过滤调用
        Result result = filter.invoke(invoker, invocation);
        assertThat(result).isNotNull();

        // 4. 断言出站 RPC Attachment 已成功挂载全链路凭据
        var clientAttachment = RpcContext.getClientAttachment();
        assertThat(clientAttachment.getAttachment(MessageHeaderConstants.TRACE_ID)).isEqualTo("trace-client-12345");
        assertThat(clientAttachment.getAttachment(MessageHeaderConstants.USER_ID)).isEqualTo("user-8888");
        assertThat(clientAttachment.getAttachment(MessageHeaderConstants.TENANT_ID)).isEqualTo("tenant-666");
        assertThat(clientAttachment.getAttachment(MessageHeaderConstants.IGNORE_TENANT)).isEqualTo("true");
        assertThat(clientAttachment.getAttachment(DubboContextPropagationFilter.TRAFFIC_TAG)).isEqualTo("gray-canary-v2");
        assertThat(clientAttachment.getAttachment(DubboSeataPropagationSupport.TX_XID)).isEqualTo("192.168.1.100:8091:202610310001");
    }

    @Test
    @DisplayName("服务端入站: 正确还原全链路上下文并在执行后 finally 确定性绝对清理")
    void testProviderInboundAndDeterministicCleanup() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("processOrder");
        invocation.setAttachment(MessageHeaderConstants.TRACE_ID, "trace-provider-9999");
        invocation.setAttachment(MessageHeaderConstants.USER_ID, "user-9999");
        invocation.setAttachment(MessageHeaderConstants.TENANT_ID, "tenant-9999");
        invocation.setAttachment(MessageHeaderConstants.IGNORE_TENANT, "true");
        invocation.setAttachment(DubboContextPropagationFilter.TRAFFIC_TAG, "tag-provider-prod");
        invocation.setAttachment(DubboSeataPropagationSupport.TX_XID, "10.0.0.5:8091:202610318888");

        when(invoker.invoke(any())).thenAnswer(invocationOnMock -> {
            // 校验在 Provider 业务线程执行期间，上下文被完整还原
            assertThat(MDC.get(MessageHeaderConstants.TRACE_ID)).isEqualTo("trace-provider-9999");
            assertThat(UserContextHolder.getUserId()).isEqualTo("user-9999");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-9999");
            assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();
            assertThat(TrafficContextHolder.getTag()).isEqualTo("tag-provider-prod");
            assertThat(RootContext.getXID()).isEqualTo("10.0.0.5:8091:202610318888");
            return new AppResponse("success");
        });

        // 执行调用
        Result result = filter.invoke(invoker, invocation);
        assertThat(result).isNotNull();

        // 校验方法结束后，finally 块必须彻底排空清理所有上下文，杜绝线程污染
        assertThat(UserContextHolder.getUserId()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
        assertThat(TrafficContextHolder.getTag()).isNull();
        assertThat(RootContext.getXID()).isNull();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("服务端入站异常时: finally 确定性清理依然绝对生效")
    void testProviderInboundExceptionCleanup() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("throwError");
        invocation.setAttachment(MessageHeaderConstants.USER_ID, "user-error");
        invocation.setAttachment(DubboSeataPropagationSupport.TX_XID, "10.0.0.1:8091:111111");

        when(invoker.invoke(any())).thenThrow(new RuntimeException("Simulated business failure"));

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated business failure");

        // 异常退出后上下文依然必须被 100% 清理
        assertThat(UserContextHolder.getUserId()).isNull();
        assertThat(RootContext.getXID()).isNull();
    }

    @Test
    @DisplayName("Injvm 本地短路调用协议直接放行，跳过出入站透传")
    void testInjvmBypass() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("injvm://127.0.0.1/io.github.atengk.cloud.dubbo.api.DemoService");
        when(invoker.getUrl()).thenReturn(url);

        RpcInvocation invocation = new RpcInvocation();
        when(invoker.invoke(any())).thenReturn(new AppResponse("injvm-pass"));

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("injvm-pass");
    }

    @Test
    @DisplayName("当配置属性禁用上下文透传时，直接短路放行")
    void testDisabledProperties() {
        AtengDubboProperties props = new AtengDubboProperties();
        props.getContext().setEnabled(false);
        DubboPropertiesHolder.set(props);

        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer");
        when(invoker.getUrl()).thenReturn(url);

        UserContextHolder.setUserId("user-should-not-propagate");

        RpcInvocation invocation = new RpcInvocation();
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        filter.invoke(invoker, invocation);

        assertThat(RpcContext.getClientAttachment().getAttachment(MessageHeaderConstants.USER_ID)).isNull();
    }
}
