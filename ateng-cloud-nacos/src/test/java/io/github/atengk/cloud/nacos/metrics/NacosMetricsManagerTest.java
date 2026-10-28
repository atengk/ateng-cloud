package io.github.atengk.cloud.nacos.metrics;

import io.github.atengk.cloud.nacos.lifecycle.NacosInstanceLifecycleState;
import io.github.atengk.cloud.nacos.lifecycle.NacosLifecycleStateMachine;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link NacosMetricsManager} Micrometer 全维监控标尺单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class NacosMetricsManagerTest {

    private SimpleMeterRegistry meterRegistry;
    private NacosTemplate nacosTemplate;
    private NacosLifecycleStateMachine stateMachine;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        nacosTemplate = mock(NacosTemplate.class);
        stateMachine = mock(NacosLifecycleStateMachine.class);
        environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "test-service");
    }

    @Test
    @DisplayName("Micrometer 指标注册：验证 5 组核心指标成功注册并导出")
    void testCoreMetricsRegistration() {
        when(nacosTemplate.isDiscoveryAvailable()).thenReturn(true);
        when(nacosTemplate.isConfigAvailable()).thenReturn(true);
        when(nacosTemplate.getConfigServerStatus()).thenReturn("UP");
        when(nacosTemplate.getHealthyInstances("test-service")).thenReturn(List.of(mock(com.alibaba.nacos.api.naming.pojo.Instance.class)));
        when(stateMachine.getState()).thenReturn(NacosInstanceLifecycleState.UP);

        NacosMetricsManager metricsManager = new NacosMetricsManager(
                meterRegistry,
                mockObjectProvider(nacosTemplate),
                mockObjectProvider(stateMachine),
                environment
        );

        // 1. nacos.grpc.connection.status (Gauge)
        Gauge grpcGauge = meterRegistry.find("nacos.grpc.connection.status").gauge();
        assertThat(grpcGauge).isNotNull();
        assertThat(grpcGauge.value()).isEqualTo(1.0);

        // 2. nacos.config.refresh.total (Counter)
        Counter refreshTotalCounter = meterRegistry.find("nacos.config.refresh.total").counter();
        assertThat(refreshTotalCounter).isNotNull();
        assertThat(refreshTotalCounter.count()).isEqualTo(0.0);

        // 3. nacos.config.refresh.failure (Counter)
        Counter refreshFailureCounter = meterRegistry.find("nacos.config.refresh.failure").counter();
        assertThat(refreshFailureCounter).isNotNull();
        assertThat(refreshFailureCounter.count()).isEqualTo(0.0);

        // 4. nacos.instances.healthy (Gauge)
        Gauge healthyGauge = meterRegistry.find("nacos.instances.healthy").gauge();
        assertThat(healthyGauge).isNotNull();
        assertThat(healthyGauge.value()).isEqualTo(1.0);

        // 5. nacos.lifecycle.state (Gauge)
        Gauge lifecycleGauge = meterRegistry.find("nacos.lifecycle.state").gauge();
        assertThat(lifecycleGauge).isNotNull();
        assertThat(lifecycleGauge.value()).isEqualTo(1.0);

        // 6. nacos.instance.warmup.remaining.seconds (Gauge)
        Gauge warmupGauge = meterRegistry.find("nacos.instance.warmup.remaining.seconds").gauge();
        assertThat(warmupGauge).isNotNull();
        assertThat(warmupGauge.value()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("热更计数递增：验证成功与失败时 Counter 正确累加")
    void testRefreshCounterIncrement() {
        NacosMetricsManager metricsManager = new NacosMetricsManager(
                meterRegistry,
                mockObjectProvider(nacosTemplate),
                mockObjectProvider(stateMachine),
                environment
        );

        Counter total = meterRegistry.find("nacos.config.refresh.total").counter();
        Counter failure = meterRegistry.find("nacos.config.refresh.failure").counter();

        // 成功热更
        metricsManager.recordRefreshSuccess(15L, 3);
        assertThat(total.count()).isEqualTo(1.0);
        assertThat(failure.count()).isEqualTo(0.0);

        // 失败热更
        metricsManager.recordRefreshFailure(25L, "Listener error");
        assertThat(total.count()).isEqualTo(2.0);
        assertThat(failure.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("动态状态变化：gRPC 断连与生命周期状态流转正确反映在 Gauge 指标上")
    void testDynamicGaugeReflections() {
        when(nacosTemplate.isConfigAvailable()).thenReturn(false);
        when(nacosTemplate.getHealthyInstances("test-service")).thenReturn(Collections.emptyList());
        when(stateMachine.getState()).thenReturn(NacosInstanceLifecycleState.OUT_OF_SERVICE);

        NacosMetricsManager metricsManager = new NacosMetricsManager(
                meterRegistry,
                mockObjectProvider(nacosTemplate),
                mockObjectProvider(stateMachine),
                environment
        );

        Gauge grpcGauge = meterRegistry.find("nacos.grpc.connection.status").gauge();
        Gauge healthyGauge = meterRegistry.find("nacos.instances.healthy").gauge();
        Gauge lifecycleGauge = meterRegistry.find("nacos.lifecycle.state").gauge();

        assertThat(grpcGauge.value()).isEqualTo(0.0);
        assertThat(healthyGauge.value()).isEqualTo(0.0);
        assertThat(lifecycleGauge.value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("多模块长连接独立隔离：Config 断连时 Naming 重连不可覆盖异常状态")
    void testModuleIndependentConnectionStatus() {
        when(nacosTemplate.isConfigAvailable()).thenReturn(true);
        when(nacosTemplate.getConfigServerStatus()).thenReturn("UP");

        NacosMetricsManager metricsManager = new NacosMetricsManager(
                meterRegistry,
                mockObjectProvider(nacosTemplate),
                mockObjectProvider(stateMachine),
                environment
        );

        Gauge grpcGauge = meterRegistry.find("nacos.grpc.connection.status").gauge();
        assertThat(grpcGauge.value()).isEqualTo(1.0);

        // 1. Config 模块断连
        metricsManager.recordGrpcDisconnect("CONFIG", "127.0.0.1:9848");
        assertThat(metricsManager.isModuleConnected("CONFIG")).isFalse();
        assertThat(grpcGauge.value()).isEqualTo(0.0);

        // 2. Naming 模块重连，不得覆盖 Config 断连告警
        metricsManager.recordGrpcConnect("NAMING", "127.0.0.1:9848");
        assertThat(metricsManager.isModuleConnected("NAMING")).isTrue();
        assertThat(grpcGauge.value()).isEqualTo(0.0);

        // 3. 只有 Config 也重连后，全局状态才恢复为 1.0
        metricsManager.recordGrpcConnect("CONFIG", "127.0.0.1:9848");
        assertThat(metricsManager.isModuleConnected("CONFIG")).isTrue();
        assertThat(grpcGauge.value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("预热剩余秒数标尺：验证启动预热中与预热完成时 Gauge 准确导出")
    void testWarmupRemainingSecondsMetric() {
        long now = System.currentTimeMillis();
        long startupTime = now - 20_000L; // 已启动 20s，预热 60s -> 剩余 40s

        io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider metadataProvider =
                mock(io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider.class);
        when(metadataProvider.getMetadata()).thenReturn(Map.of(
                io.github.atengk.cloud.nacos.metadata.DefaultNacosMetadataProvider.KEY_STARTUP_TIME, String.valueOf(startupTime),
                io.github.atengk.cloud.nacos.metadata.DefaultNacosMetadataProvider.KEY_WARMUP_DURATION, "60s"
        ));

        io.github.atengk.cloud.nacos.properties.AtengNacosProperties properties =
                new io.github.atengk.cloud.nacos.properties.AtengNacosProperties();

        NacosMetricsManager metricsManager = new NacosMetricsManager(
                meterRegistry,
                mockObjectProvider(nacosTemplate),
                mockObjectProvider(stateMachine),
                environment,
                mockObjectProvider(metadataProvider),
                properties
        );

        Gauge warmupGauge = meterRegistry.find("nacos.instance.warmup.remaining.seconds").gauge();
        assertThat(warmupGauge).isNotNull();
        assertThat(warmupGauge.value()).isLessThanOrEqualTo(40.0);
        assertThat(warmupGauge.value()).isGreaterThanOrEqualTo(35.0);
    }

    @Test
    @DisplayName("MeterRegistry 为 null 时无异常防御执行")
    void testNullMeterRegistrySafety() {
        NacosMetricsManager metricsManager = new NacosMetricsManager(
                null,
                mockObjectProvider(nacosTemplate),
                mockObjectProvider(stateMachine),
                environment
        );

        // 不应抛出任何异常
        metricsManager.recordRefreshSuccess(10L, 1);
        metricsManager.recordRefreshFailure(10L, "error");
        assertThat(metricsManager.isEnabled()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockObjectProvider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
