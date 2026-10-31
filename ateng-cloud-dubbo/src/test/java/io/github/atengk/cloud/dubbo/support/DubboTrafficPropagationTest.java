package io.github.atengk.cloud.dubbo.support;

import io.github.atengk.cloud.dubbo.filter.DubboContextPropagationFilter;
import io.github.atengk.cloud.dubbo.filter.DubboTrafficTagFilter;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
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
 * Dubbo 全链路流量染色与 TrafficContextHolder 泳道元数据双向穿透与确定性清理单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class DubboTrafficPropagationTest {

    @AfterEach
    void tearDown() {
        TrafficContextHolder.clear();
        MDC.clear();
        RpcContext.removeClientAttachment();
        RpcContext.removeServerAttachment();
        DubboPropertiesHolder.reset();
    }

    @Test
    @DisplayName("消费端出站自动从 TrafficContextHolder 提取流量标签、泳道名与匹配规则并注入 Attachment")
    void testConsumerExtractsAndInjectsTrafficContext() {
        TrafficContextHolder.setContext(TrafficContext.of("gray-v3", "payment-lane", "rule-999"));

        DubboContextPropagationFilter filter = new DubboContextPropagationFilter();
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        URL consumerUrl = URL.valueOf("tri://127.0.0.1:20880/TestService?side=consumer");
        when(invoker.getUrl()).thenReturn(consumerUrl);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("payOrder");

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isNotNull();
        assertThat(invocation.getAttachment(DubboTrafficPropagationSupport.TRAFFIC_TAG)).isEqualTo("gray-v3");
        assertThat(invocation.getAttachment(DubboTrafficPropagationSupport.TRAFFIC_LANE)).isEqualTo("payment-lane");
        assertThat(invocation.getAttachment(DubboTrafficPropagationSupport.TRAFFIC_RULE)).isEqualTo("rule-999");
        assertThat(RpcContext.getClientAttachment().getAttachment(DubboTrafficPropagationSupport.TRAFFIC_TAG))
                .isEqualTo("gray-v3");
    }

    @Test
    @DisplayName("当 TrafficContextHolder 无上下文时消费端平滑回退支持 MDC 流量染色标记")
    void testConsumerMdcFallbackWhenTrafficContextHolderEmpty() {
        MDC.put(DubboTrafficPropagationSupport.TRAFFIC_TAG, "canary-mdc");

        DubboContextPropagationFilter filter = new DubboContextPropagationFilter();
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        URL consumerUrl = URL.valueOf("tri://127.0.0.1:20880/TestService?side=consumer");
        when(invoker.getUrl()).thenReturn(consumerUrl);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryStatus");

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isNotNull();
        assertThat(invocation.getAttachment(DubboTrafficPropagationSupport.TRAFFIC_TAG)).isEqualTo("canary-mdc");
    }

    @Test
    @DisplayName("服务端入站提取完整泳道上下文并还原至 TrafficContextHolder，出口 finally 块确定性清理")
    void testProviderExtractsAndRestoresTrafficContext() {
        DubboContextPropagationFilter filter = new DubboContextPropagationFilter();
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        URL providerUrl = URL.valueOf("tri://127.0.0.1:20880/TestService?side=provider");
        when(invoker.getUrl()).thenReturn(providerUrl);

        AtomicReference<TrafficContext> providerObservedContext = new AtomicReference<>();
        when(invoker.invoke(any())).thenAnswer(inv -> {
            providerObservedContext.set(TrafficContextHolder.getContext());
            return new AppResponse("ok");
        });

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("handleTask");
        invocation.setAttachment(DubboTrafficPropagationSupport.TRAFFIC_TAG, "gray-service-a");
        invocation.setAttachment(DubboTrafficPropagationSupport.TRAFFIC_LANE, "order-lane");
        invocation.setAttachment(DubboTrafficPropagationSupport.TRAFFIC_RULE, "rule-order-1");

        Result result = filter.invoke(invoker, invocation);

        assertThat(result).isNotNull();
        // 验证执行服务业务时已成功还原 TrafficContext
        TrafficContext context = providerObservedContext.get();
        assertThat(context).isNotNull();
        assertThat(context.getTag()).isEqualTo("gray-service-a");
        assertThat(context.getLaneName()).isEqualTo("order-lane");
        assertThat(context.getMatchedRule()).isEqualTo("rule-order-1");

        // 验证执行完成后在 finally 块中已被完全清理
        assertThat(TrafficContextHolder.getContext()).isNull();
        assertThat(MDC.get(DubboTrafficPropagationSupport.TRAFFIC_TAG)).isNull();
    }

    @Test
    @DisplayName("服务端在抛出异常时依然在 finally 块中确定性清理 TrafficContextHolder")
    void testProviderCleansUpTrafficContextOnException() {
        DubboContextPropagationFilter filter = new DubboContextPropagationFilter();
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        URL providerUrl = URL.valueOf("tri://127.0.0.1:20880/TestService?side=provider");
        when(invoker.getUrl()).thenReturn(providerUrl);
        when(invoker.invoke(any())).thenThrow(new RpcException("Provider execution failure"));

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("faultMethod");
        invocation.setAttachment(DubboTrafficPropagationSupport.TRAFFIC_TAG, "error-tag");
        invocation.setAttachment(DubboTrafficPropagationSupport.TRAFFIC_LANE, "fault-lane");

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("Provider execution failure");

        // 验证发生异常时上下文依然彻底释放
        assertThat(TrafficContextHolder.getContext()).isNull();
        assertThat(MDC.get(DubboTrafficPropagationSupport.TRAFFIC_TAG)).isNull();
    }

    @Test
    @DisplayName("验证 DubboTrafficTagFilter 优先读取 TrafficContextHolder 并注入 Dubbo 标签路由参数")
    void testTrafficTagFilterIntegratesWithTrafficContextHolder() {
        TrafficContextHolder.setContext(TrafficContext.of("lane-v2", "user-lane", "rule-user"));

        DubboTrafficTagFilter filter = new DubboTrafficTagFilter();
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        URL consumerUrl = URL.valueOf("tri://127.0.0.1:20880/TestService?side=consumer");
        when(invoker.getUrl()).thenReturn(consumerUrl);
        when(invoker.getInterface()).thenReturn((Class) Object.class);
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("getUser");

        filter.invoke(invoker, invocation);

        assertThat(invocation.getAttachment(CommonConstants.TAG_KEY)).isEqualTo("lane-v2");
        assertThat(invocation.getAttachment("tag")).isEqualTo("lane-v2");
    }

    @Test
    @DisplayName("验证 DubboTrafficPropagationSupport 边缘防御与空安全防卫")
    void testDubboTrafficPropagationSupportEdgeCases() {
        assertThat(DubboTrafficPropagationSupport.isTrafficAvailable()).isTrue();

        // 空值与空白字符串还原防御
        DubboTrafficPropagationSupport.restore(null, null, null);
        assertThat(TrafficContextHolder.getContext()).isNull();

        DubboTrafficPropagationSupport.restore("   ", "lane", "rule");
        assertThat(TrafficContextHolder.getContext()).isNull();

        DubboTrafficPropagationSupport.restore("null", "lane", "rule");
        assertThat(TrafficContextHolder.getContext()).isNull();

        // 幂等清理
        DubboTrafficPropagationSupport.clear();
        assertThat(TrafficContextHolder.getContext()).isNull();
        assertThat(MDC.get(DubboTrafficPropagationSupport.TRAFFIC_LANE)).isNull();
    }

    @Test
    @DisplayName("验证纯泳道场景下元数据还原至 MDC 且清空时不影响外部业务 MDC 属性")
    void testPureLaneRestorationAndSelectiveMdcCleanup() {
        // 1. 设置外部业务自带的 MDC 属性
        MDC.put("bizTrace", "custom-biz-12345");

        // 2. 模拟仅携带泳道名称与规则的纯泳道请求
        DubboTrafficPropagationSupport.restore(null, "checkout-lane", "rule-gray-checkout");
        assertThat(MDC.get(DubboTrafficPropagationSupport.TRAFFIC_LANE)).isEqualTo("checkout-lane");
        assertThat(MDC.get(DubboTrafficPropagationSupport.TRAFFIC_RULE)).isEqualTo("rule-gray-checkout");
        assertThat(MDC.get("bizTrace")).isEqualTo("custom-biz-12345");

        // 3. 执行上下文清理
        DubboTrafficPropagationSupport.clear();
        assertThat(MDC.get(DubboTrafficPropagationSupport.TRAFFIC_LANE)).isNull();
        assertThat(MDC.get(DubboTrafficPropagationSupport.TRAFFIC_RULE)).isNull();
        assertThat(MDC.get("bizTrace")).isEqualTo("custom-biz-12345");

        MDC.clear();
    }
}
