package io.github.atengk.cloud.dubbo.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.properties.AtengDubboSlowCallProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import io.github.atengk.cloud.dubbo.support.DubboSlowCallMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Dubbo Slow Call Detection 慢调用监测过滤器单元测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
class DubboSlowCallFilterTest {

    private final DubboSlowCallFilter filter = new DubboSlowCallFilter();
    private SimpleMeterRegistry meterRegistry;
    private Logger filterLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        DubboPropertiesHolder.reset();
        DubboSlowCallMetrics.reset();
        MDC.clear();

        meterRegistry = new SimpleMeterRegistry();
        new DubboSlowCallMetrics().bindTo(meterRegistry);

        filterLogger = (Logger) LoggerFactory.getLogger(DubboSlowCallFilter.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        filterLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (filterLogger != null && logAppender != null) {
            filterLogger.detachAppender(logAppender);
            logAppender.stop();
        }
        MDC.clear();
        DubboSlowCallMetrics.reset();
        DubboPropertiesHolder.reset();
    }

    @Test
    @DisplayName("验证 SPI 注册表中 slowCall 在 Consumer 与 Provider 端均正确激活")
    void testFilterSpiRegistration() {
        ExtensionLoader<Filter> loader = ApplicationModel.defaultModel()
                .getDefaultModule()
                .getExtensionLoader(Filter.class);

        assertThat(loader.hasExtension("slowCall")).isTrue();
        Filter spiFilter = loader.getExtension("slowCall");
        assertThat(spiFilter).isInstanceOf(DubboSlowCallFilter.class);
    }

    @Test
    @DisplayName("Consumer 端同步耗时超过阈值时输出含全维字段的 WARN 日志并累加 dubbo.rpc.slow.calls 指标")
    void testConsumerSlowCallTriggersWarnLogAndMetric() {
        configureSlowCall(true, Duration.ofMillis(30), true, true);
        MDC.put("traceId", "trace-consumer-slow-001");

        Invoker<?> invoker = mockInvoker("tri://127.0.0.1:50051/DemoService?side=consumer", DemoService.class);
        RpcInvocation invocation = new RpcInvocation("echo", DemoService.class.getName(), null,
                new Class<?>[]{String.class}, new Object[]{"hi"});
        when(invoker.invoke(invocation)).thenAnswer(inv -> {
            Thread.sleep(60L);
            return new AppResponse("ok");
        });

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("ok");

        List<ILoggingEvent> warnLogs = findWarnLogs();
        assertThat(warnLogs).hasSize(1);
        String msg = warnLogs.get(0).getFormattedMessage();
        assertThat(msg)
                .contains("side=consumer")
                .contains("service=" + DemoService.class.getName())
                .contains("method=echo")
                .contains("elapsedMs=")
                .contains("thresholdMs=30")
                .contains("traceId=trace-consumer-slow-001");

        Counter counter = meterRegistry.find(DubboSlowCallMetrics.METRIC_SLOW_CALLS)
                .tag("side", CommonConstants.CONSUMER_SIDE)
                .tag("service", DemoService.class.getName())
                .tag("method", "echo")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("Provider 端耗时超过阈值时从 Attachment/MDC 提取 traceId 并记录慢调用指标")
    void testProviderSlowCallWithAttachmentTraceId() {
        configureSlowCall(true, Duration.ofMillis(30), true, true);

        Invoker<?> invoker = mockInvoker("tri://127.0.0.1:50051/DemoService?side=provider", DemoService.class);
        RpcInvocation invocation = new RpcInvocation("echo", DemoService.class.getName(), null,
                new Class<?>[]{String.class}, new Object[]{"hi"});
        invocation.setAttachment("X-Trace-Id", "trace-provider-slow-002");
        when(invoker.invoke(invocation)).thenAnswer(inv -> {
            Thread.sleep(55L);
            return new AppResponse("ok");
        });

        filter.invoke(invoker, invocation);

        List<ILoggingEvent> warnLogs = findWarnLogs();
        assertThat(warnLogs).hasSize(1);
        assertThat(warnLogs.get(0).getFormattedMessage())
                .contains("side=provider")
                .contains("traceId=trace-provider-slow-002");

        Counter counter = meterRegistry.find(DubboSlowCallMetrics.METRIC_SLOW_CALLS)
                .tag("side", CommonConstants.PROVIDER_SIDE)
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("正常耗时低于阈值时不误报慢调用")
    void testFastCallDoesNotTriggerSlowCall() {
        configureSlowCall(true, Duration.ofMillis(500), true, true);

        Invoker<?> invoker = mockInvoker("tri://127.0.0.1:50051/DemoService?side=consumer", DemoService.class);
        RpcInvocation invocation = new RpcInvocation("echo", DemoService.class.getName(), null,
                new Class<?>[]{String.class}, new Object[]{"fast"});
        when(invoker.invoke(invocation)).thenReturn(new AppResponse("ok"));

        filter.invoke(invoker, invocation);

        assertThat(findWarnLogs()).isEmpty();
        assertThat(meterRegistry.find(DubboSlowCallMetrics.METRIC_SLOW_CALLS).counter()).isNull();
    }

    @Test
    @DisplayName("异步 AsyncRpcResult 在 onResponse 阶段完成计时且不会重复统计")
    void testAsyncResultDeferredMeasurementOnResponse() throws Exception {
        configureSlowCall(true, Duration.ofMillis(30), true, true);
        MDC.put("traceId", "trace-async-003");

        Invoker<?> invoker = mockInvoker("tri://127.0.0.1:50051/DemoService?side=consumer", DemoService.class);
        RpcInvocation invocation = new RpcInvocation("echo", DemoService.class.getName(), null,
                new Class<?>[]{String.class}, new Object[]{"async"});
        CompletableFuture<AppResponse> future = new CompletableFuture<>();
        AsyncRpcResult pendingResult = new AsyncRpcResult(future, invocation);
        when(invoker.invoke(invocation)).thenReturn(pendingResult);

        Result returned = filter.invoke(invoker, invocation);
        assertThat(returned).isSameAs(pendingResult);
        // 异步尚未完成时不提前记录
        assertThat(findWarnLogs()).isEmpty();

        Thread.sleep(55L);
        MDC.clear(); // 模拟 Netty 回调线程已无 MDC
        AppResponse completed = new AppResponse("async-ok");
        future.complete(completed);
        filter.onResponse(completed, invoker, invocation);
        // 重复回调亦不重复计数
        filter.onResponse(completed, invoker, invocation);

        List<ILoggingEvent> warnLogs = findWarnLogs();
        assertThat(warnLogs).hasSize(1);
        assertThat(warnLogs.get(0).getFormattedMessage()).contains("traceId=trace-async-003");
        Counter counter = meterRegistry.find(DubboSlowCallMetrics.METRIC_SLOW_CALLS).counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("调用抛出 RpcException 且耗时超阈值时先记录慢调用再原样重抛异常")
    void testSlowCallOnThrownException() {
        configureSlowCall(true, Duration.ofMillis(30), true, true);

        Invoker<?> invoker = mockInvoker("tri://127.0.0.1:50051/DemoService?side=consumer", DemoService.class);
        RpcInvocation invocation = new RpcInvocation("echo", DemoService.class.getName(), null,
                new Class<?>[]{String.class}, new Object[]{"err"});
        when(invoker.invoke(invocation)).thenAnswer(inv -> {
            Thread.sleep(50L);
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "mock timeout");
        });

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("mock timeout");

        assertThat(findWarnLogs()).hasSize(1);
        Counter counter = meterRegistry.find(DubboSlowCallMetrics.METRIC_SLOW_CALLS).counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("injvm 短路调用与 StreamObserver 流式调用强制豁免慢调用监测")
    void testInjvmAndStreamInvocationsAreExempted() {
        configureSlowCall(true, Duration.ofMillis(10), true, true);

        Invoker<?> injvmInvoker = mockInvoker("injvm://127.0.0.1/DemoService?side=consumer", DemoService.class);
        RpcInvocation unaryInvocation = new RpcInvocation("echo", DemoService.class.getName(), null,
                new Class<?>[]{String.class}, new Object[]{"local"});
        when(injvmInvoker.invoke(unaryInvocation)).thenAnswer(inv -> {
            Thread.sleep(30L);
            return new AppResponse("local-ok");
        });
        filter.invoke(injvmInvoker, unaryInvocation);

        Invoker<?> streamInvoker = mockInvoker("tri://127.0.0.1:50051/DemoService?side=consumer", DemoService.class);
        RpcInvocation streamInvocation = new RpcInvocation("streamCall", DemoService.class.getName(), null,
                new Class<?>[]{StreamObserver.class}, new Object[]{Mockito.mock(StreamObserver.class)});
        when(streamInvoker.invoke(streamInvocation)).thenAnswer(inv -> {
            Thread.sleep(30L);
            return new AppResponse();
        });
        filter.invoke(streamInvoker, streamInvocation);

        assertThat(findWarnLogs()).isEmpty();
        assertThat(meterRegistry.find(DubboSlowCallMetrics.METRIC_SLOW_CALLS).counter()).isNull();
    }

    @Test
    @DisplayName("支持按 Consumer/Provider 角色开关、总开关与非正阈值关闭，且无 MeterRegistry 时安全降级")
    void testToggleAndNoMeterRegistryFallback() {
        DubboSlowCallMetrics.reset();
        configureSlowCall(true, Duration.ofMillis(10), false, true);

        Invoker<?> consumerInvoker = mockInvoker("tri://127.0.0.1:50051/DemoService?side=consumer", DemoService.class);
        RpcInvocation invocation = new RpcInvocation("echo", DemoService.class.getName(), null,
                new Class<?>[]{String.class}, new Object[]{"c"});
        when(consumerInvoker.invoke(invocation)).thenAnswer(inv -> {
            Thread.sleep(25L);
            return new AppResponse("ok");
        });
        filter.invoke(consumerInvoker, invocation);
        assertThat(findWarnLogs()).isEmpty();

        // Provider 仍启用且无 MeterRegistry 时仅打日志不抛 NPE
        Invoker<?> providerInvoker = mockInvoker("tri://127.0.0.1:50051/DemoService?side=provider", DemoService.class);
        when(providerInvoker.invoke(invocation)).thenAnswer(inv -> {
            Thread.sleep(25L);
            return new AppResponse("ok");
        });
        filter.invoke(providerInvoker, invocation);
        assertThat(findWarnLogs()).hasSize(1);

        // 非正阈值关闭
        logAppender.list.clear();
        configureSlowCall(true, Duration.ZERO, true, true);
        filter.invoke(providerInvoker, invocation);
        assertThat(findWarnLogs()).isEmpty();

        // slow-call.enabled=false 直接放行
        logAppender.list.clear();
        configureSlowCall(false, Duration.ofMillis(10), true, true);
        filter.invoke(providerInvoker, invocation);
        assertThat(findWarnLogs()).isEmpty();
    }

    @Test
    @DisplayName("异步 AsyncRpcResult 在 onError 回调阶段完成慢调用计时且不重复统计")
    void testAsyncResultDeferredMeasurementOnError() throws Exception {
        configureSlowCall(true, Duration.ofMillis(30), true, true);
        MDC.put("traceId", "trace-async-err-004");

        Invoker<?> invoker = mockInvoker("tri://127.0.0.1:50051/DemoService?side=consumer", DemoService.class);
        RpcInvocation invocation = new RpcInvocation("echo", DemoService.class.getName(), null,
                new Class<?>[]{String.class}, new Object[]{"async-err"});
        CompletableFuture<AppResponse> future = new CompletableFuture<>();
        AsyncRpcResult pendingResult = new AsyncRpcResult(future, invocation);
        when(invoker.invoke(invocation)).thenReturn(pendingResult);

        filter.invoke(invoker, invocation);
        assertThat(findWarnLogs()).isEmpty();

        Thread.sleep(55L);
        MDC.clear();
        RpcException asyncError = new RpcException(RpcException.TIMEOUT_EXCEPTION, "async timeout");
        future.completeExceptionally(asyncError);
        filter.onError(asyncError, invoker, invocation);
        filter.onError(asyncError, invoker, invocation);

        List<ILoggingEvent> warnLogs = findWarnLogs();
        assertThat(warnLogs).hasSize(1);
        assertThat(warnLogs.get(0).getFormattedMessage()).contains("traceId=trace-async-err-004");
        Counter counter = meterRegistry.find(DubboSlowCallMetrics.METRIC_SLOW_CALLS).counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0d);
    }

    private void configureSlowCall(boolean enabled, Duration threshold, boolean includeConsumer, boolean includeProvider) {
        AtengDubboProperties properties = new AtengDubboProperties();
        AtengDubboSlowCallProperties slowCall = new AtengDubboSlowCallProperties();
        slowCall.setEnabled(enabled);
        slowCall.setThreshold(threshold);
        slowCall.setIncludeConsumer(includeConsumer);
        slowCall.setIncludeProvider(includeProvider);
        properties.setSlowCall(slowCall);
        DubboPropertiesHolder.set(properties);
    }

    @SuppressWarnings("unchecked")
    private <T> Invoker<T> mockInvoker(String urlStr, Class<T> type) {
        Invoker<T> invoker = Mockito.mock(Invoker.class);
        when(invoker.getInterface()).thenReturn(type);
        when(invoker.getUrl()).thenReturn(URL.valueOf(urlStr));
        return invoker;
    }

    private List<ILoggingEvent> findWarnLogs() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
    }

    interface DemoService {
        String echo(String msg);

        void streamCall(StreamObserver<String> observer);
    }
}
