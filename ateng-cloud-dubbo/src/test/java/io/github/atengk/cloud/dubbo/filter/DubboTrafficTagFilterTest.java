package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import io.github.atengk.cloud.dubbo.support.DubboTrafficPropagationSupport;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Constants;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
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

    interface DemoService {
        String pay();
    }
}
