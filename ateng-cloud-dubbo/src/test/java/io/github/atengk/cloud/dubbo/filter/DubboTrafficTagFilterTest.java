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
import org.apache.dubbo.rpc.Constants;
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
import static org.mockito.Mockito.when;

/**
 * Dubbo 消费端金丝雀流量染色与标签路由平滑降级过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class DubboTrafficTagFilterTest {

    private final DubboTrafficTagFilter filter = new DubboTrafficTagFilter();

    @BeforeEach
    void setUp() {
        DubboPropertiesHolder.set(new AtengDubboProperties());
        cleanContext();
    }

    @AfterEach
    void tearDown() {
        DubboPropertiesHolder.reset();
        cleanContext();
    }

    private void cleanContext() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        if (DubboSeataPropagationSupport.isSeataAvailable() && RootContext.inGlobalTransaction()) {
            RootContext.unbind();
        }
        TrafficContextHolder.clear();
        MDC.clear();
        RpcContext.removeClientAttachment();
        RpcContext.removeServerAttachment();
    }

    @Test
    @DisplayName("优先从 TrafficContextHolder 提取流量标签并注入 Dubbo 原生标签路由参数")
    void testInjectTagFromTrafficContextHolder() {
        TrafficContextHolder.setContext(TrafficContext.of("gray-v1", "pay-lane", "rule-vip"));

        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("pay");
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("ok");

        var clientAttachment = RpcContext.getClientAttachment();
        assertThat(clientAttachment.getAttachment(CommonConstants.TAG_KEY)).isEqualTo("gray-v1");
        assertThat(clientAttachment.getAttachment("tag")).isEqualTo("gray-v1");
        assertThat(clientAttachment.getAttachment(Constants.FORCE_USE_TAG)).isEqualTo("false");
        assertThat(clientAttachment.getAttachment(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER)).isEqualTo("gray-v1");
    }

    @Test
    @DisplayName("当 TrafficContextHolder 为空时，优雅从 MDC 提取染色标签")
    void testInjectTagFromMdc() {
        MDC.put(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER, "canary-mdc");

        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("order");
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        filter.invoke(invoker, invocation);

        assertThat(RpcContext.getClientAttachment().getAttachment(CommonConstants.TAG_KEY)).isEqualTo("canary-mdc");
    }

    @Test
    @DisplayName("tagForce=false 平滑降级: 目标服务实例不带标签时正常放行")
    void testSmoothFallbackWhenTagForceFalse() {
        TrafficContextHolder.setContext(TrafficContext.of("gray-v2"));

        Invoker<?> invoker = mock(Invoker.class);
        // Provider URL 不带 tag 参数，模拟基线无标签实例
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        when(invoker.invoke(any())).thenReturn(new AppResponse("fallback-baseline-ok"));

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("fallback-baseline-ok");
    }

    @Test
    @DisplayName("tagForce=true 强隔离: 目标服务具备相同标签时允许调用")
    void testTagForceTrueMatchesProviderTag() {
        TrafficContextHolder.setContext(TrafficContext.of("gray-strict"));

        AtengDubboProperties props = new AtengDubboProperties();
        props.getTraffic().setTagForce(true);
        DubboPropertiesHolder.set(props);

        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?tag=gray-strict");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        when(invoker.invoke(any())).thenReturn(new AppResponse("strict-matched"));

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("strict-matched");
    }

    @Test
    @DisplayName("tagForce=true 强隔离拦截: 目标服务标签不匹配时 Fail-Fast 抛出 RpcException")
    void testTagForceTrueMismatchThrowsRpcException() {
        TrafficContextHolder.setContext(TrafficContext.of("gray-strict"));

        AtengDubboProperties props = new AtengDubboProperties();
        props.getTraffic().setTagForce(true);
        DubboPropertiesHolder.set(props);

        Invoker<?> invoker = mock(Invoker.class);
        // Provider URL 不具备 gray-strict 标签
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?tag=baseline");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("criticalOp");

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("No provider available for the service")
                .hasMessageContaining("under tag gray-strict");
    }

    @Test
    @DisplayName("配置属性禁用流量治理特性时直接跳过")
    void testFeatureDisabled() {
        AtengDubboProperties props = new AtengDubboProperties();
        props.getTraffic().setEnabled(false);
        DubboPropertiesHolder.set(props);

        TrafficContextHolder.setContext(TrafficContext.of("gray-disabled"));

        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        filter.invoke(invoker, invocation);
        assertThat(RpcContext.getClientAttachment().getAttachment(CommonConstants.TAG_KEY)).isNull();
    }

    @Test
    @DisplayName("消费端出站前置对称对齐: 同线程清空标签上下文后二次调用彻底清除受管路由键并保留业务自定义附件")
    void testPreInvokeReconciliationRemovesStaleRouteAttachments() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        // 1. 第 1 次调用：挂载 TrafficContext 并设置自定义非受管附件
        TrafficContextHolder.setContext(TrafficContext.of("gray-v1", "lane-1", "rule-1"));
        RpcContext.getClientAttachment().setAttachment("X-Custom-Biz", "biz-keep");
        RpcInvocation firstInvocation = new RpcInvocation();
        firstInvocation.setMethodName("pay");
        filter.invoke(invoker, firstInvocation);

        assertThat(RpcContext.getClientAttachment().getAttachment(CommonConstants.TAG_KEY)).isEqualTo("gray-v1");
        assertThat(RpcContext.getClientAttachment().getAttachment("dubbo.tagforce")).isEqualTo("false");

        // 2. 清空本地上下文（不触发 onResponse 直接模拟线程复用，且二次 invocation 预带脏路由键）
        TrafficContextHolder.clear();
        MDC.clear();

        RpcInvocation secondInvocation = new RpcInvocation();
        secondInvocation.setMethodName("pay");
        secondInvocation.setAttachment(CommonConstants.TAG_KEY, "gray-v1");
        secondInvocation.setAttachment("tag", "gray-v1");
        secondInvocation.setAttachment(Constants.FORCE_USE_TAG, "true");
        secondInvocation.setAttachment("dubbo.tagforce", "true");
        secondInvocation.setAttachment("tagforce", "true");
        secondInvocation.setAttachment("X-Req-Custom", "req-keep");

        filter.invoke(invoker, secondInvocation);

        // 3. 断言受管路由键从 ClientAttachment 与 RpcInvocation 全部清除，自定义非受管附件保留
        var clientAttachment = RpcContext.getClientAttachment();
        assertManagedRouteAttachmentsNull(clientAttachment, DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER);
        assertThat(clientAttachment.getAttachment("X-Custom-Biz")).isEqualTo("biz-keep");

        assertManagedRouteAttachmentsNull(secondInvocation, DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER);
        assertThat(secondInvocation.getAttachment("X-Req-Custom")).isEqualTo("req-keep");
    }

    @Test
    @DisplayName("tagForce 阻断清洗两端出站键且不受陈旧 ClientAttachment.FORCE_USE_TAG 反向污染")
    void testTagForceIgnoresStaleClientAttachmentAndCleansInvocationOnReject() {
        // 1. 模拟陈旧 ClientAttachment 残留 dubbo.force.tag=true 并经 ConsumerContextFilter 镜像拷入新 Invocation，
        // 新调用显式声明 dubbo.tagforce=false 且切换新标签时，显式参数优先并覆盖回退为 false
        RpcContext.getClientAttachment().setAttachment(Constants.FORCE_USE_TAG, "true");
        MDC.put(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER, "gray-v2");

        Invoker<?> baselineInvoker = mock(Invoker.class);
        URL baselineUrl = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService");
        when(baselineInvoker.getUrl()).thenReturn(baselineUrl);
        when(baselineInvoker.getInterface()).thenReturn((Class) DemoService.class);
        when(baselineInvoker.invoke(any())).thenReturn(new AppResponse("baseline-ok"));

        RpcInvocation normalCall = new RpcInvocation();
        normalCall.setMethodName("pay");
        normalCall.addObjectAttachments(RpcContext.getClientAttachment().getObjectAttachments());
        normalCall.setAttachment("dubbo.tagforce", "false");
        Result result = filter.invoke(baselineInvoker, normalCall);
        assertThat(result.getValue()).isEqualTo("baseline-ok");
        assertThat(normalCall.getAttachment(Constants.FORCE_USE_TAG)).isEqualTo("false");

        // 1b. 模拟前次调用未走 onResponse 回调导致 ClientAttachment 遗留成套路由快照 (tagForce=true)，
        // 同线程新调用切换配置 tagForce=false 并由 ConsumerContextFilter 拷贝镜像后，仍必须采信当前配置而非陈旧镜像
        RpcContext.removeClientAttachment();
        AtengDubboProperties strictProps = new AtengDubboProperties();
        strictProps.getTraffic().setTagForce(true);
        DubboPropertiesHolder.set(strictProps);
        Invoker<?> v1Invoker = mock(Invoker.class);
        when(v1Invoker.getUrl()).thenReturn(URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?tag=gray-v1"));
        when(v1Invoker.getInterface()).thenReturn((Class) DemoService.class);
        when(v1Invoker.invoke(any())).thenReturn(new AppResponse("v1-ok"));
        TrafficContextHolder.setTag("gray-v1");
        RpcInvocation firstCall = new RpcInvocation();
        firstCall.setMethodName("pay");
        filter.invoke(v1Invoker, firstCall);

        AtengDubboProperties relaxedProps = new AtengDubboProperties();
        relaxedProps.getTraffic().setTagForce(false);
        DubboPropertiesHolder.set(relaxedProps);
        TrafficContextHolder.setTag("gray-v2");
        RpcInvocation echoedStaleCall = new RpcInvocation();
        echoedStaleCall.setMethodName("pay");
        echoedStaleCall.addObjectAttachments(RpcContext.getClientAttachment().getObjectAttachments());
        Result fallbackResult = filter.invoke(baselineInvoker, echoedStaleCall);
        assertThat(fallbackResult.getValue()).isEqualTo("baseline-ok");
        assertThat(echoedStaleCall.getAttachment(Constants.FORCE_USE_TAG)).isEqualTo("false");
        assertThat(echoedStaleCall.getAttachment("dubbo.tagforce")).isEqualTo("false");
        assertThat(echoedStaleCall.getAttachment("tagforce")).isEqualTo("false");
        assertThat(RpcContext.getClientAttachment().getAttachment(Constants.FORCE_USE_TAG)).isEqualTo("false");
        RpcContext.removeClientAttachment();

        // 1c. 验证调用方通过 RpcContext.getClientAttachment().setAttachment(FORCE_USE_TAG, "true") 显式发起单次强隔离时仍正常生效
        RpcContext.getClientAttachment().setAttachment(Constants.FORCE_USE_TAG, "true");
        RpcInvocation clientExplicitStrictCall = new RpcInvocation();
        clientExplicitStrictCall.setMethodName("pay");
        clientExplicitStrictCall.addObjectAttachments(RpcContext.getClientAttachment().getObjectAttachments());
        assertThatThrownBy(() -> filter.invoke(baselineInvoker, clientExplicitStrictCall))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("under tag gray-v2");
        RpcContext.removeClientAttachment();
        DubboPropertiesHolder.reset();

        // 2. 当 invocation 显式强制 tagForce=true 且标签不匹配时，清理 ClientAttachment 全部受管路由键与 invocation 标签键，
        // 同时保留 invocation 上的 tagForce 强制标记以确保 FailoverClusterInvoker 重试仍严格拒绝对基线节点降级
        RpcInvocation strictCall = new RpcInvocation();
        strictCall.setMethodName("pay");
        strictCall.setAttachment(Constants.FORCE_USE_TAG, "true");
        strictCall.setAttachment(CommonConstants.TAG_KEY, "stale-tag");
        RpcContext.getClientAttachment().setAttachment("dubbo.tag", "stale-tag");
        RpcContext.getClientAttachment().setAttachment("X-Keep-Me", "custom");

        assertThatThrownBy(() -> filter.invoke(baselineInvoker, strictCall))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("under tag gray-v2");
        assertManagedRouteAttachmentsNull(RpcContext.getClientAttachment(), DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER);
        assertThat(strictCall.getAttachment(CommonConstants.TAG_KEY)).isNull();
        assertThat(strictCall.getAttachment("tag")).isNull();
        assertThat(strictCall.getAttachment(Constants.FORCE_USE_TAG)).isEqualTo("true");
        assertThat(RpcContext.getClientAttachment().getAttachment("X-Keep-Me")).isEqualTo("custom");

        // 模拟 Dubbo FailoverClusterInvoker 携带同一 RpcInvocation 重试基线节点：依然严格阻断而不退化为 tagForce=false
        assertThatThrownBy(() -> filter.invoke(baselineInvoker, strictCall))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("under tag gray-v2");
        assertManagedRouteAttachmentsNull(RpcContext.getClientAttachment(), DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER);

        // 模拟 FailoverClusterInvoker 后续重试命中匹配灰度节点时正常路由通过
        Invoker<?> grayInvoker = mock(Invoker.class);
        URL grayUrl = URL.valueOf("tri://127.0.0.1:50052/io.github.atengk.cloud.dubbo.api.DemoService?tag=gray-v2");
        when(grayInvoker.getUrl()).thenReturn(grayUrl);
        when(grayInvoker.getInterface()).thenReturn((Class) DemoService.class);
        when(grayInvoker.invoke(any())).thenReturn(new AppResponse("gray-ok"));

        Result retryGrayResult = filter.invoke(grayInvoker, strictCall);
        assertThat(retryGrayResult.getValue()).isEqualTo("gray-ok");
        assertThat(strictCall.getAttachment(CommonConstants.TAG_KEY)).isEqualTo("gray-v2");
        assertThat(strictCall.getAttachment(Constants.FORCE_USE_TAG)).isEqualTo("true");
    }

    @Test
    @DisplayName("消费端 onResponse 与 onError 回调清理受管路由出站键并支持自定义 headerName，同时保留业务附件")
    void testOnResponseAndOnErrorCleanManagedRouteAttachmentsPreservingCustom() {
        AtengDubboProperties props = new AtengDubboProperties();
        props.getTraffic().setHeaderName("X-Canary-Tag");
        DubboPropertiesHolder.set(props);

        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        // 1. onResponse
        MDC.put("X-Canary-Tag", "canary-resp");
        RpcContext.getClientAttachment().setAttachment("X-Custom-Header", "keep-resp");
        RpcInvocation respInvocation = new RpcInvocation();
        respInvocation.setMethodName("pay");

        Result result = filter.invoke(invoker, respInvocation);
        assertThat(RpcContext.getClientAttachment().getAttachment("X-Canary-Tag")).isEqualTo("canary-resp");
        filter.onResponse(result, invoker, respInvocation);

        assertManagedRouteAttachmentsNull(RpcContext.getClientAttachment(), "X-Canary-Tag");
        assertThat(RpcContext.getClientAttachment().getAttachment("X-Custom-Header")).isEqualTo("keep-resp");

        // 2. onError
        RpcContext.getClientAttachment().setAttachment("X-Custom-Header", "keep-err");
        RpcInvocation errInvocation = new RpcInvocation();
        errInvocation.setMethodName("pay");
        filter.invoke(invoker, errInvocation);
        filter.onError(new RpcException("downstream error"), invoker, errInvocation);

        assertManagedRouteAttachmentsNull(RpcContext.getClientAttachment(), "X-Canary-Tag");
        assertThat(RpcContext.getClientAttachment().getAttachment("X-Custom-Header")).isEqualTo("keep-err");
    }

    @Test
    @DisplayName("消费端完整出站链 (4D Context -> Tag Router) 同线程连续多次调用无 IgnoreTenant/XID/TrafficTag 残留")
    void testSameThreadConsecutiveChainCallsHaveNoCrossCallLeakage() {
        DubboContextPropagationFilter contextFilter = new DubboContextPropagationFilter();
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer&tag=gray-chain");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        // 1. 第 1 次调用：挂载 X-Ignore-Tenant=true、Seata TX_XID、X-Traffic-Tag 与自定义业务附件
        TenantContextHolder.setIgnoreTenant(true);
        RootContext.bind("192.168.10.1:8091:99887766");
        TrafficContextHolder.setContext(TrafficContext.of("gray-chain", "chain-lane", "rule-chain"));
        RpcContext.getClientAttachment().setAttachment("X-Biz-Trace", "keep-across-calls");

        RpcInvocation firstInvocation = new RpcInvocation();
        firstInvocation.setMethodName("pay");
        firstInvocation.setAttachment(Constants.FORCE_USE_TAG, "true");
        contextFilter.invoke(invocationChainInvoker(filter, invoker), firstInvocation);

        assertThat(firstInvocation.getAttachment(MessageHeaderConstants.IGNORE_TENANT)).isEqualTo("true");
        assertThat(firstInvocation.getAttachment(DubboSeataPropagationSupport.TX_XID))
                .isEqualTo("192.168.10.1:8091:99887766");
        assertThat(firstInvocation.getAttachment(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER))
                .isEqualTo("gray-chain");
        assertThat(firstInvocation.getAttachment(CommonConstants.TAG_KEY)).isEqualTo("gray-chain");

        // 2. 清空全部本地上下文后在同一工作线程直接发起第 2 次调用（模拟 ConsumerContextFilter 把残留 ClientAttachment 复制进 invocation）
        TenantContextHolder.clear();
        RootContext.unbind();
        TrafficContextHolder.clear();
        MDC.clear();

        RpcInvocation secondInvocation = new RpcInvocation();
        secondInvocation.setMethodName("pay");
        secondInvocation.addObjectAttachments(RpcContext.getClientAttachment().getObjectAttachments());

        contextFilter.invoke(invocationChainInvoker(filter, invoker), secondInvocation);

        // 3. 断言第 2 次出站请求及 ClientAttachment 中前次受管键全部被清除，仅业务非受管附件保留
        assertThat(secondInvocation.getAttachment(MessageHeaderConstants.IGNORE_TENANT)).isNull();
        assertThat(secondInvocation.getAttachment(DubboSeataPropagationSupport.TX_XID)).isNull();
        assertThat(secondInvocation.getAttachment(DubboTrafficPropagationSupport.TRAFFIC_LANE)).isNull();
        assertThat(secondInvocation.getAttachment(DubboTrafficPropagationSupport.TRAFFIC_RULE)).isNull();
        assertManagedRouteAttachmentsNull(secondInvocation, DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER);
        assertThat(secondInvocation.getAttachment("X-Biz-Trace")).isEqualTo("keep-across-calls");

        assertThat(RpcContext.getClientAttachment().getAttachment(MessageHeaderConstants.IGNORE_TENANT)).isNull();
        assertThat(RpcContext.getClientAttachment().getAttachment(DubboSeataPropagationSupport.TX_XID)).isNull();
        assertManagedRouteAttachmentsNull(RpcContext.getClientAttachment(), DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER);
        assertThat(RpcContext.getClientAttachment().getAttachment("X-Biz-Trace")).isEqualTo("keep-across-calls");

        // 4. 仅通过 Invocation 传入默认 X-Traffic-Tag（ClientAttachment 干净且本地线程无标签）可穿透 ContextFilter(-10000) 并命中路由
        RpcInvocation explicitDefaultHeaderInvocation = new RpcInvocation();
        explicitDefaultHeaderInvocation.setMethodName("pay");
        explicitDefaultHeaderInvocation.setAttachment(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER, "inv-default-canary");
        contextFilter.invoke(invocationChainInvoker(filter, invoker), explicitDefaultHeaderInvocation);

        assertThat(explicitDefaultHeaderInvocation.getAttachment(CommonConstants.TAG_KEY)).isEqualTo("inv-default-canary");
        assertThat(explicitDefaultHeaderInvocation.getAttachment(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER))
                .isEqualTo("inv-default-canary");
        filter.onResponse(new AppResponse("ok"), invoker, explicitDefaultHeaderInvocation);
        contextFilter.onResponse(new AppResponse("ok"), invoker, explicitDefaultHeaderInvocation);

        // 5. 自定义 headerName 场景：先验证仅 Invocation 传入可正常路由，再模拟 ConsumerContextFilter 拷贝陈旧 ClientAttachment 绝不复活
        AtengDubboProperties customProps = new AtengDubboProperties();
        customProps.getTraffic().setHeaderName("X-Canary-Tag");
        DubboPropertiesHolder.set(customProps);

        RpcInvocation customInvCall = new RpcInvocation();
        customInvCall.setMethodName("pay");
        customInvCall.setAttachment("X-Canary-Tag", "inv-custom-canary");
        contextFilter.invoke(invocationChainInvoker(filter, invoker), customInvCall);
        assertThat(customInvCall.getAttachment(CommonConstants.TAG_KEY)).isEqualTo("inv-custom-canary");
        assertThat(customInvCall.getAttachment("X-Canary-Tag")).isEqualTo("inv-custom-canary");

        // 模拟未走 onResponse 且随后本地清空，ConsumerContextFilter 将 X-Canary-Tag 拷贝至下一次 Invocation
        RpcInvocation leakedCustomCall = new RpcInvocation();
        leakedCustomCall.setMethodName("pay");
        leakedCustomCall.addObjectAttachments(RpcContext.getClientAttachment().getObjectAttachments());
        contextFilter.invoke(invocationChainInvoker(filter, invoker), leakedCustomCall);

        assertManagedRouteAttachmentsNull(leakedCustomCall, "X-Canary-Tag");
        assertManagedRouteAttachmentsNull(RpcContext.getClientAttachment(), "X-Canary-Tag");
    }

    private void assertManagedRouteAttachmentsNull(Invocation invocation, String headerName) {
        assertThat(invocation.getAttachment(CommonConstants.TAG_KEY)).isNull();
        assertThat(invocation.getAttachment("tag")).isNull();
        assertThat(invocation.getAttachment(Constants.FORCE_USE_TAG)).isNull();
        assertThat(invocation.getAttachment("dubbo.tagforce")).isNull();
        assertThat(invocation.getAttachment("tagforce")).isNull();
        assertThat(invocation.getAttachment(headerName)).isNull();
        assertThat(invocation.getAttachment(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER)).isNull();
    }

    private void assertManagedRouteAttachmentsNull(org.apache.dubbo.rpc.RpcContextAttachment attachment, String headerName) {
        assertThat(attachment.getAttachment(CommonConstants.TAG_KEY)).isNull();
        assertThat(attachment.getAttachment("tag")).isNull();
        assertThat(attachment.getAttachment(Constants.FORCE_USE_TAG)).isNull();
        assertThat(attachment.getAttachment("dubbo.tagforce")).isNull();
        assertThat(attachment.getAttachment("tagforce")).isNull();
        assertThat(attachment.getAttachment(headerName)).isNull();
        assertThat(attachment.getAttachment(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER)).isNull();
    }

    private Invoker<?> invocationChainInvoker(DubboTrafficTagFilter nextFilter, Invoker<?> targetInvoker) {
        URL url = targetInvoker.getUrl();
        Class<?> serviceInterface = targetInvoker.getInterface();
        Invoker<?> chainInvoker = mock(Invoker.class);
        when(chainInvoker.getUrl()).thenReturn(url);
        when(chainInvoker.getInterface()).thenReturn((Class) serviceInterface);
        when(chainInvoker.invoke(any())).thenAnswer(inv -> {
            Invocation arg = inv.getArgument(0);
            return nextFilter.invoke(targetInvoker, arg);
        });
        return chainInvoker;
    }

    interface DemoService {
        String pay();
    }
}
