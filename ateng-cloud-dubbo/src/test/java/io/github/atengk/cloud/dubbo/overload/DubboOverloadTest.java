package io.github.atengk.cloud.dubbo.overload;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.threadpool.event.ThreadPoolExhaustedEvent;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dubbo 线程池过载保护、拒绝策略与统计观测单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class DubboOverloadTest {

    @BeforeEach
    void setUp() {
        DubboPropertiesHolder.set(new AtengDubboProperties());
        DubboOverloadListener.reset();
    }

    @AfterEach
    void tearDown() {
        DubboPropertiesHolder.reset();
        DubboOverloadListener.reset();
    }

    @Test
    @DisplayName("DubboOverloadListener: 事件派发、拒绝计数与时间戳重置")
    void testDubboOverloadListener() {
        assertThat(DubboOverloadListener.getRejectedCount()).isEqualTo(0);
        assertThat(DubboOverloadListener.getLastExhaustedTimestamp()).isEqualTo(0);

        DubboOverloadListener listener = new DubboOverloadListener();
        ThreadPoolExhaustedEvent event = new ThreadPoolExhaustedEvent("ThreadPool full on port 20880");
        listener.onEvent(event);

        assertThat(DubboOverloadListener.getRejectedCount()).isEqualTo(1);
        assertThat(DubboOverloadListener.getLastExhaustedTimestamp()).isGreaterThan(0);

        DubboOverloadListener.incrementRejectCount();
        assertThat(DubboOverloadListener.getRejectedCount()).isEqualTo(2);

        DubboOverloadListener.reset();
        assertThat(DubboOverloadListener.getRejectedCount()).isEqualTo(0);
        assertThat(DubboOverloadListener.getLastExhaustedTimestamp()).isEqualTo(0);
    }

    @Test
    @DisplayName("DubboOverloadHandler: 线程池拒绝时抛出 TOO_MANY_REQUESTS BusinessException 并记录诊断")
    void testDubboOverloadHandler() {
        DubboOverloadHandler handler = new DubboOverloadHandler("TestWorkerPool");

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                handler
        );

        assertThatThrownBy(() -> handler.rejectedExecution(() -> {}, executor))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode());
                    assertThat(be.getMessage()).contains("服务端并发线程池已耗尽");
                });

        assertThat(DubboOverloadListener.getRejectedCount()).isEqualTo(1);
        executor.shutdown();
    }

    @Test
    @DisplayName("DubboOverloadMetrics: 绑定 Micrometer 并动态反映拒绝指标与线程池状态")
    void testDubboOverloadMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DubboOverloadMetrics metrics = new DubboOverloadMetrics();
        metrics.bindTo(registry);

        FunctionCounter rejectCounter = registry.find("dubbo.threadpool.reject").functionCounter();
        assertThat(rejectCounter).isNotNull();
        assertThat(rejectCounter.count()).isEqualTo(0.0);

        DubboOverloadListener.incrementRejectCount();
        DubboOverloadListener.incrementRejectCount();

        assertThat(rejectCounter.count()).isEqualTo(2.0);

        // 注册测试线程池并校验其各项指标
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                5, 10, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(50)
        );
        metrics.registerCustomExecutor(registry, "test-pool", executor);

        Gauge coreGauge = registry.find("dubbo.threadpool.core").tag("pool", "test-pool").gauge();
        assertThat(coreGauge).isNotNull();
        assertThat(coreGauge.value()).isEqualTo(5.0);

        Gauge maxGauge = registry.find("dubbo.threadpool.max").tag("pool", "test-pool").gauge();
        assertThat(maxGauge).isNotNull();
        assertThat(maxGauge.value()).isEqualTo(10.0);

        executor.shutdown();
    }

    @Test
    @DisplayName("DubboOverloadFilter: 同步捕获过载 RpcException 并转译为 TOO_MANY_REQUESTS")
    void testDubboOverloadFilterCatchesRpcException() {
        DubboOverloadFilter filter = new DubboOverloadFilter();
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) OverloadService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("processJob");

        RpcException overloadEx = new RpcException("Thread pool is EXHAUSTED on provider!");
        when(invoker.invoke(any())).thenThrow(overloadEx);

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode());
                    assertThat(be.getCause()).isSameAs(overloadEx);
                });

        assertThat(DubboOverloadListener.getRejectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DubboOverloadFilter: onResponse 拦截过载异常并转译为 BusinessException")
    void testDubboOverloadFilterOnResponse() {
        DubboOverloadFilter filter = new DubboOverloadFilter();
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) OverloadService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("processJob");

        AppResponse response = new AppResponse(new RejectedExecutionException("Worker threads can not be used anymore"));
        filter.onResponse(response, invoker, invocation);

        assertThat(response.hasException()).isTrue();
        assertThat(response.getException()).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) response.getException();
        assertThat(be.getCode()).isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode());
        assertThat(response.getValue()).isNull();
    }

    @Test
    @DisplayName("DubboOverloadThreadPool: 构造线程池并挂载 DubboOverloadHandler 拒绝策略")
    void testDubboOverloadThreadPool() {
        DubboOverloadThreadPool threadPool = new DubboOverloadThreadPool();
        URL url = URL.valueOf("dubbo://127.0.0.1:20880?threads=2&threadname=CustomOverloadPool");

        Executor executor = threadPool.getExecutor(url);
        assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);

        ThreadPoolExecutor poolExecutor = (ThreadPoolExecutor) executor;
        assertThat(poolExecutor.getRejectedExecutionHandler()).isInstanceOf(DubboOverloadHandler.class);
        poolExecutor.shutdown();
    }

    interface OverloadService {
        void processJob();
    }
}
