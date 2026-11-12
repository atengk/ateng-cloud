package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import io.github.atengk.cloud.dubbo.support.DubboSeataPropagationSupport;
import io.github.atengk.cloud.dubbo.support.DubboTrafficPropagationSupport;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcContextAttachment;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;

import java.util.function.Function;

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
        DubboPropertiesHolder.reset();
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

    @Test
    @DisplayName("消费端出站前置对称对齐: 同线程清空本地上下文后二次调用彻底消除前次残留附件并保留业务自定义附件")
    void testConsumerPreInvokeReconciliationRemovesStaleManagedAttachments() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        // 1. 第 1 次调用：挂载全量四维上下文、IgnoreTenant、Traffic 泳道与 Seata XID，以及业务方非受管附件
        MDC.put(MessageHeaderConstants.TRACE_ID, "trace-call-1");
        UserContextHolder.setUserId("user-call-1");
        TenantContextHolder.setTenantId("tenant-call-1");
        TenantContextHolder.setIgnoreTenant(true);
        TrafficContextHolder.setContext(TrafficContext.of("gray-v1", "lane-1", "rule-1"));
        RootContext.bind("10.0.0.1:8091:111111");
        RpcContext.getClientAttachment().setAttachment("X-Custom-Biz", "biz-keep");

        RpcInvocation firstInvocation = new RpcInvocation();
        firstInvocation.setMethodName("step1");
        filter.invoke(invoker, firstInvocation);

        assertThat(RpcContext.getClientAttachment().getAttachment(MessageHeaderConstants.IGNORE_TENANT)).isEqualTo("true");
        assertThat(RpcContext.getClientAttachment().getAttachment(DubboSeataPropagationSupport.TX_XID))
                .isEqualTo("10.0.0.1:8091:111111");

        // 2. 清空全部本地上下文（模拟未经过 onResponse 直接同线程复用，且二次 invocation 预带脏附件）
        UserContextHolder.clear();
        TenantContextHolder.clear();
        TrafficContextHolder.clear();
        MDC.clear();
        RootContext.unbind();

        RpcInvocation secondInvocation = new RpcInvocation();
        secondInvocation.setMethodName("step2");
        secondInvocation.setAttachment(MessageHeaderConstants.TRACE_ID, "trace-call-1");
        secondInvocation.setAttachment(MessageHeaderConstants.USER_ID, "user-call-1");
        secondInvocation.setAttachment(MessageHeaderConstants.TENANT_ID, "tenant-call-1");
        secondInvocation.setAttachment(MessageHeaderConstants.IGNORE_TENANT, "true");
        secondInvocation.setAttachment(DubboContextPropagationFilter.TRAFFIC_TAG, "gray-v1");
        secondInvocation.setAttachment(DubboTrafficPropagationSupport.TRAFFIC_LANE, "lane-1");
        secondInvocation.setAttachment(DubboTrafficPropagationSupport.TRAFFIC_RULE, "rule-1");
        secondInvocation.setAttachment(DubboSeataPropagationSupport.TX_XID, "10.0.0.1:8091:111111");
        secondInvocation.setAttachment(DubboSeataPropagationSupport.TX_XID_LOWERCASE, "10.0.0.1:8091:111111");
        secondInvocation.setAttachment("X-Req-Biz", "req-keep");

        filter.invoke(invoker, secondInvocation);

        // 3. 断言 ClientAttachment 与 RpcInvocation 受管键全部清除，非受管业务附件完好保留
        var clientAttachment = RpcContext.getClientAttachment();
        assertConsumerManagedAttachmentsNull(clientAttachment);
        assertThat(clientAttachment.getAttachment("X-Custom-Biz")).isEqualTo("biz-keep");

        assertConsumerManagedAttachmentsNull(secondInvocation);
        assertThat(secondInvocation.getAttachment("X-Req-Biz")).isEqualTo("req-keep");
    }

    @Test
    @DisplayName("消费端切换新 Seata TX_XID 与显式 Invocation X-Traffic-Tag：清洗旧 tx_xid 且保留非镜像单次请求头")
    void testConsumerReconcilesLowercaseXidSwitchAndPreservesExplicitInvocationTag() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        // 1. 激活新 XID 时，旧 tx_xid 必须被彻底清洗，避免与新 TX_XID 双值并发
        RootContext.bind("10.0.0.2:8091:999999");
        RpcContext.getClientAttachment().setAttachment(DubboSeataPropagationSupport.TX_XID_LOWERCASE, "10.0.0.1:8091:111111");
        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(DubboSeataPropagationSupport.TX_XID_LOWERCASE, "10.0.0.1:8091:111111");
        invocation.setAttachment(DubboContextPropagationFilter.TRAFFIC_TAG, "explicit-inv-tag");

        filter.invoke(invoker, invocation);

        assertThat(RpcContext.getClientAttachment().getAttachment(DubboSeataPropagationSupport.TX_XID))
                .isEqualTo("10.0.0.2:8091:999999");
        assertThat(invocation.getAttachment(DubboSeataPropagationSupport.TX_XID))
                .isEqualTo("10.0.0.2:8091:999999");
        assertThat(RpcContext.getClientAttachment().getAttachment(DubboSeataPropagationSupport.TX_XID_LOWERCASE)).isNull();
        assertThat(invocation.getAttachment(DubboSeataPropagationSupport.TX_XID_LOWERCASE)).isNull();
        // 非 ClientAttachment 镜像拷贝的 Invocation 显式标签应保留给下游 DubboTrafficTagFilter 解析
        assertThat(invocation.getAttachment(DubboContextPropagationFilter.TRAFFIC_TAG)).isEqualTo("explicit-inv-tag");
    }

    @Test
    @DisplayName("消费端只读 Invocation 包装对象执行前置清理不抛异常")
    void testConsumerReadOnlyInvocationAttachmentsSafeCleanup() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        Invocation readOnlyInvocation = mock(Invocation.class);
        java.util.Map<String, Object> frozenMap = java.util.Collections.unmodifiableMap(
                java.util.Map.of(MessageHeaderConstants.IGNORE_TENANT, "true"));
        when(readOnlyInvocation.getObjectAttachments()).thenReturn(frozenMap);
        when(readOnlyInvocation.getAttachment(any())).thenAnswer(inv -> {
            Object v = frozenMap.get(inv.getArgument(0, String.class));
            return v != null ? v.toString() : null;
        });

        Result result = filter.invoke(invoker, readOnlyInvocation);
        assertThat(result.getValue()).isEqualTo("ok");
        assertThat(RpcContext.getClientAttachment().getAttachment(MessageHeaderConstants.IGNORE_TENANT)).isNull();
    }

    @Test
    @DisplayName("消费端 onResponse 与 onError 回调闭环清理受管出站附件键，同时保留业务方自定义非受管附件")
    void testConsumerOnResponseAndOnErrorCleanManagedClientAttachments() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        // 1. onResponse 场景
        UserContextHolder.setUserId("user-resp");
        TenantContextHolder.setIgnoreTenant(true);
        TrafficContextHolder.setContext(TrafficContext.of("gray-resp", "lane-resp", "rule-resp"));
        RootContext.bind("10.0.0.2:8091:222222");
        RpcContext.getClientAttachment().setAttachment("X-Custom-Header", "keep-on-response");

        RpcInvocation respInvocation = new RpcInvocation();
        Result result = filter.invoke(invoker, respInvocation);
        filter.onResponse(result, invoker, respInvocation);

        assertConsumerManagedAttachmentsNull(RpcContext.getClientAttachment());
        assertThat(RpcContext.getClientAttachment().getAttachment("X-Custom-Header")).isEqualTo("keep-on-response");

        // 2. onError 场景
        RpcContext.getClientAttachment().setAttachment("X-Custom-Header", "keep-on-error");
        RpcInvocation errInvocation = new RpcInvocation();
        filter.invoke(invoker, errInvocation);
        filter.onError(new org.apache.dubbo.rpc.RpcException("rpc timeout"), invoker, errInvocation);

        assertConsumerManagedAttachmentsNull(RpcContext.getClientAttachment());
        assertThat(RpcContext.getClientAttachment().getAttachment("X-Custom-Header")).isEqualTo("keep-on-error");
    }

    private void assertConsumerManagedAttachmentsNull(RpcContextAttachment attachment) {
        assertConsumerManagedAttachmentsNull(attachment::getAttachment);
    }

    private void assertConsumerManagedAttachmentsNull(Invocation invocation) {
        assertConsumerManagedAttachmentsNull(invocation::getAttachment);
    }

    private void assertConsumerManagedAttachmentsNull(Function<String, String> attachmentGetter) {
        assertThat(attachmentGetter.apply(MessageHeaderConstants.TRACE_ID)).isNull();
        assertThat(attachmentGetter.apply(MessageHeaderConstants.USER_ID)).isNull();
        assertThat(attachmentGetter.apply(MessageHeaderConstants.TENANT_ID)).isNull();
        assertThat(attachmentGetter.apply(MessageHeaderConstants.IGNORE_TENANT)).isNull();
        assertThat(attachmentGetter.apply(DubboContextPropagationFilter.TRAFFIC_TAG)).isNull();
        assertThat(attachmentGetter.apply(DubboTrafficPropagationSupport.TRAFFIC_LANE)).isNull();
        assertThat(attachmentGetter.apply(DubboTrafficPropagationSupport.TRAFFIC_RULE)).isNull();
        assertThat(attachmentGetter.apply(DubboSeataPropagationSupport.TX_XID)).isNull();
        assertThat(attachmentGetter.apply(DubboSeataPropagationSupport.TX_XID_LOWERCASE)).isNull();
    }
}
