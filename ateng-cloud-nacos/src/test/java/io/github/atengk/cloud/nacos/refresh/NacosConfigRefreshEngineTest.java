package io.github.atengk.cloud.nacos.refresh;

import com.alibaba.cloud.nacos.refresh.NacosConfigRefreshEvent;
import io.github.atengk.cloud.nacos.event.ConfigChangeItem;
import io.github.atengk.cloud.nacos.event.ConfigChangeType;
import io.github.atengk.cloud.nacos.event.SafeConfigRefreshEvent;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.spi.NacosConfigChangeListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link NacosConfigRefreshEngine} 核心引擎单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class NacosConfigRefreshEngineTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("基线快照与精准新旧值对比：准确计算 ADDED / MODIFIED / DELETED 变更类型")
    void testComputeChangeDetails() {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> initialProps = new HashMap<>();
        initialProps.put("sample.datasource.url", "jdbc:mysql://host1/db");
        initialProps.put("sample.cache.ttl", "60s");
        initialProps.put("sample.deprecated.key", "old-val");
        environment.getPropertySources().addFirst(new MapPropertySource("initialSource", initialProps));

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AtengNacosProperties properties = new AtengNacosProperties();

        NacosConfigRefreshEngine engine = new NacosConfigRefreshEngine(
                environment,
                publisher,
                Collections.emptyList(),
                null,
                properties
        );

        // 1. 初始化快照
        engine.afterSingletonsInstantiated();
        assertThat(engine.getPropertySnapshot()).containsKeys("sample.datasource.url", "sample.cache.ttl", "sample.deprecated.key");

        // 2. 模拟环境属性发生更新：修改 url，新增 new-feature，删除 deprecated.key
        initialProps.put("sample.datasource.url", "jdbc:mysql://host2/db");
        initialProps.put("sample.new-feature.enabled", "true");
        initialProps.remove("sample.deprecated.key");

        Set<String> changedKeys = Set.of("sample.datasource.url", "sample.new-feature.enabled", "sample.deprecated.key");
        SafeConfigRefreshEvent event = engine.refreshConfig(changedKeys, "app.yml", "DEFAULT_GROUP");

        assertThat(event.getChangedKeys()).containsExactlyInAnyOrderElementsOf(changedKeys);

        // 校验变更类型
        ConfigChangeItem modItem = event.getChange("sample.datasource.url");
        assertThat(modItem.getChangeType()).isEqualTo(ConfigChangeType.MODIFIED);
        assertThat(modItem.getOldValue()).isEqualTo("jdbc:mysql://host1/db");
        assertThat(modItem.getNewValue()).isEqualTo("jdbc:mysql://host2/db");

        ConfigChangeItem addItem = event.getChange("sample.new-feature.enabled");
        assertThat(addItem.getChangeType()).isEqualTo(ConfigChangeType.ADDED);
        assertThat(addItem.getOldValue()).isNull();
        assertThat(addItem.getNewValue()).isEqualTo("true");

        ConfigChangeItem delItem = event.getChange("sample.deprecated.key");
        assertThat(delItem.getChangeType()).isEqualTo(ConfigChangeType.DELETED);
        assertThat(delItem.getOldValue()).isEqualTo("old-val");
        assertThat(delItem.getNewValue()).isNull();

        // 验证快照同步更新
        assertThat(engine.getPropertySnapshot().get("sample.datasource.url")).isEqualTo("jdbc:mysql://host2/db");
        assertThat(engine.getPropertySnapshot().get("sample.new-feature.enabled")).isEqualTo("true");
        assertThat(engine.getPropertySnapshot()).doesNotContainKey("sample.deprecated.key");
    }

    @Test
    @DisplayName("SPI 前缀过滤与排序：精确匹配前缀且依 getOrder() 严格顺序执行")
    void testListenerPrefixFilteringAndOrdering() {
        StandardEnvironment environment = new StandardEnvironment();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AtengNacosProperties properties = new AtengNacosProperties();

        List<String> executionOrder = new ArrayList<>();

        // 监听器 1: 关注 order.pay，优先级 10
        NacosConfigChangeListener payListener = new NacosConfigChangeListener() {
            @Override
            public void onConfigChange(SafeConfigRefreshEvent event) {
                executionOrder.add("payListener");
            }

            @Override
            public String getPrefix() {
                return "order.pay";
            }

            @Override
            public int getOrder() {
                return 10;
            }
        };

        // 监听器 2: 关注 全局，优先级 5 (先执行)
        NacosConfigChangeListener globalListener = new NacosConfigChangeListener() {
            @Override
            public void onConfigChange(SafeConfigRefreshEvent event) {
                executionOrder.add("globalListener");
            }

            @Override
            public int getOrder() {
                return 5;
            }
        };

        // 监听器 3: 关注 order.inventory，优先级 1 (前缀不匹配，不执行)
        NacosConfigChangeListener invListener = new NacosConfigChangeListener() {
            @Override
            public void onConfigChange(SafeConfigRefreshEvent event) {
                executionOrder.add("invListener");
            }

            @Override
            public String getPrefix() {
                return "order.inventory";
            }

            @Override
            public int getOrder() {
                return 1;
            }
        };

        List<NacosConfigChangeListener> listeners = List.of(payListener, globalListener, invListener);

        NacosConfigRefreshEngine engine = new NacosConfigRefreshEngine(
                environment,
                publisher,
                listeners,
                null,
                properties
        );

        // 触发变更集仅包含 order.pay.timeout
        engine.refreshConfig(Set.of("order.pay.timeout"), "order.yml", "DEFAULT_GROUP");

        // 期望执行顺序：globalListener (order 5) -> payListener (order 10)；invListener 被过滤跳过
        assertThat(executionOrder).containsExactly("globalListener", "payListener");
    }

    @Test
    @DisplayName("强隔离异常捕获屏障：单个监听器抛出异常绝不阻断后续监听器与事件发布")
    void testExceptionIsolationBarrier() {
        StandardEnvironment environment = new StandardEnvironment();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AtengNacosProperties properties = new AtengNacosProperties();

        AtomicBoolean healthyListenerExecuted = new AtomicBoolean(false);

        // 故障监听器：抛出严重异常
        NacosConfigChangeListener faultyListener = new NacosConfigChangeListener() {
            @Override
            public void onConfigChange(SafeConfigRefreshEvent event) {
                throw new IllegalStateException("Simulated faulty listener crash");
            }

            @Override
            public int getOrder() {
                return 1;
            }
        };

        // 健康监听器：正常执行
        NacosConfigChangeListener healthyListener = new NacosConfigChangeListener() {
            @Override
            public void onConfigChange(SafeConfigRefreshEvent event) {
                healthyListenerExecuted.set(true);
            }

            @Override
            public int getOrder() {
                return 2;
            }
        };

        List<NacosConfigChangeListener> listeners = List.of(faultyListener, healthyListener);

        NacosConfigRefreshEngine engine = new NacosConfigRefreshEngine(
                environment,
                publisher,
                listeners,
                null,
                properties
        );

        // 执行热更，故障监听器的异常被屏障捕获隔离，healthyListener 依然正常被调用
        SafeConfigRefreshEvent event = engine.refreshConfig(Set.of("sample.config"), "app.yml", "DEFAULT_GROUP");

        assertThat(healthyListenerExecuted.get()).isTrue();
        verify(publisher).publishEvent(eq(event));
    }

    @Test
    @DisplayName("MDC 链路穿透与确定性清理：回调期间自动维持 traceId，并在 finally 彻底清理防污染")
    void testMdcTraceIdPropagationAndCleanup() {
        StandardEnvironment environment = new StandardEnvironment();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AtengNacosProperties properties = new AtengNacosProperties();

        AtomicReference<String> capturedTraceIdInListener = new AtomicReference<>();

        NacosConfigChangeListener listener = event -> {
            capturedTraceIdInListener.set(MDC.get(NacosConfigRefreshEngine.MDC_TRACE_ID_KEY));
        };
        List<NacosConfigChangeListener> listeners = List.of(listener);

        NacosConfigRefreshEngine engine = new NacosConfigRefreshEngine(
                environment,
                publisher,
                listeners,
                null,
                properties
        );

        // 确保执行前 MDC 无 traceId
        MDC.remove(NacosConfigRefreshEngine.MDC_TRACE_ID_KEY);

        SafeConfigRefreshEvent event = engine.refreshConfig(Set.of("sample.key"), "app.yml", "DEFAULT_GROUP");

        // 验证监听器内部获取到了注入的 traceId
        assertThat(capturedTraceIdInListener.get()).isNotBlank();
        assertThat(event.getTraceId()).isEqualTo(capturedTraceIdInListener.get());

        // 验证执行后 MDC 已被确定性清理，绝无线程池污染
        assertThat(MDC.get(NacosConfigRefreshEngine.MDC_TRACE_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("事件监听与属性重绑集成：捕获 NacosConfigRefreshEvent 与 EnvironmentChangeEvent")
    void testApplicationEventListenerIntegration() {
        StandardEnvironment environment = new StandardEnvironment();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ConfigurationPropertiesRebinder rebinder = mock(ConfigurationPropertiesRebinder.class);

        AtengNacosProperties properties = new AtengNacosProperties();

        NacosConfigRefreshEngine engine = new NacosConfigRefreshEngine(
                environment,
                publisher,
                Collections.emptyList(),
                rebinder,
                properties
        );

        // 1. 模拟收到 NacosConfigRefreshEvent，记录数据源标识
        NacosConfigRefreshEvent nacosEvent = mock(NacosConfigRefreshEvent.class);
        when(nacosEvent.getDataId()).thenReturn("order-service.yml");
        when(nacosEvent.getGroup()).thenReturn("ORDER_GROUP");
        engine.onApplicationEvent(nacosEvent);

        // 2. 模拟收到 EnvironmentChangeEvent，触发增量热更与 rebinder.rebind()
        EnvironmentChangeEvent envChangeEvent = new EnvironmentChangeEvent(Set.of("order.timeout"));
        engine.onApplicationEvent(envChangeEvent);

        verify(rebinder, times(1)).rebind();
        verify(publisher, times(1)).publishEvent(any(SafeConfigRefreshEvent.class));
    }

    @Test
    @DisplayName("Micrometer 指标上报与执行记录：热更成功时记录 RefreshRecord 并上报 success 标尺")
    void testRefreshRecordAndMetricsReporting() {
        StandardEnvironment environment = new StandardEnvironment();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        io.github.atengk.cloud.nacos.metrics.NacosMetricsManager metricsManager = mock(io.github.atengk.cloud.nacos.metrics.NacosMetricsManager.class);
        AtengNacosProperties properties = new AtengNacosProperties();

        NacosConfigRefreshEngine engine = new NacosConfigRefreshEngine(
                environment,
                publisher,
                Collections.emptyList(),
                null,
                properties,
                metricsManager
        );

        assertThat(engine.getLastRefreshRecord()).isNull();

        engine.refreshConfig(Set.of("app.name", "app.version"), "demo.yml", "DEFAULT_GROUP");

        NacosConfigRefreshEngine.RefreshRecord record = engine.getLastRefreshRecord();
        assertThat(record).isNotNull();
        assertThat(record.getTraceId()).isNotBlank();
        assertThat(record.getChangedKeysCount()).isEqualTo(2);
        assertThat(record.getChangedKeys()).containsExactlyInAnyOrder("app.name", "app.version");
        assertThat(record.isSuccess()).isTrue();
        assertThat(record.getError()).isNull();
        assertThat(record.getDurationMs()).isGreaterThanOrEqualTo(0);

        verify(metricsManager, times(1)).recordRefreshSuccess(anyLong(), eq(2));
        verify(metricsManager, never()).recordRefreshFailure(anyLong(), any());
    }

    @Test
    @DisplayName("监听器失败时：上报 failure 标尺并标记 record.isSuccess 为 false")
    void testListenerFailureReporting() {
        StandardEnvironment environment = new StandardEnvironment();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        io.github.atengk.cloud.nacos.metrics.NacosMetricsManager metricsManager = mock(io.github.atengk.cloud.nacos.metrics.NacosMetricsManager.class);
        AtengNacosProperties properties = new AtengNacosProperties();

        NacosConfigChangeListener faultyListener = new NacosConfigChangeListener() {
            @Override
            public void onConfigChange(SafeConfigRefreshEvent event) {
                throw new RuntimeException("Simulated error in listener");
            }
        };

        NacosConfigRefreshEngine engine = new NacosConfigRefreshEngine(
                environment,
                publisher,
                List.of(faultyListener),
                null,
                properties,
                metricsManager
        );

        engine.refreshConfig(Set.of("app.name"), "demo.yml", "DEFAULT_GROUP");

        NacosConfigRefreshEngine.RefreshRecord record = engine.getLastRefreshRecord();
        assertThat(record).isNotNull();
        assertThat(record.isSuccess()).isFalse();
        assertThat(record.getError()).contains("One or more NacosConfigChangeListener failed");

        verify(metricsManager, times(1)).recordRefreshFailure(anyLong(), contains("One or more NacosConfigChangeListener failed"));
        verify(metricsManager, never()).recordRefreshSuccess(anyLong(), anyInt());
    }

    @Test
    @DisplayName("Spring 事件发布异常：指标准确上报失败并记录状态")
    void testEventPublisherFailureReporting() {
        StandardEnvironment environment = new StandardEnvironment();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        doThrow(new RuntimeException("Simulated error in @EventListener")).when(publisher).publishEvent(any());
        io.github.atengk.cloud.nacos.metrics.NacosMetricsManager metricsManager = mock(io.github.atengk.cloud.nacos.metrics.NacosMetricsManager.class);
        AtengNacosProperties properties = new AtengNacosProperties();

        NacosConfigRefreshEngine engine = new NacosConfigRefreshEngine(
                environment,
                publisher,
                Collections.emptyList(),
                null,
                properties,
                metricsManager
        );

        engine.refreshConfig(Set.of("app.name"), "demo.yml", "DEFAULT_GROUP");

        NacosConfigRefreshEngine.RefreshRecord record = engine.getLastRefreshRecord();
        assertThat(record).isNotNull();
        assertThat(record.isSuccess()).isFalse();
        assertThat(record.getError()).contains("SafeConfigRefreshEvent publication encountered errors");

        verify(metricsManager, times(1)).recordRefreshFailure(anyLong(), contains("SafeConfigRefreshEvent publication encountered errors"));
        verify(metricsManager, never()).recordRefreshSuccess(anyLong(), anyInt());
    }

    @Test
    @DisplayName("MDC 链路注入时序前置：重绑属性期间已成功持有 traceId 上下文")
    void testMdcTraceIdAvailableDuringRebind() {
        StandardEnvironment environment = new StandardEnvironment();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ConfigurationPropertiesRebinder rebinder = mock(ConfigurationPropertiesRebinder.class);
        AtengNacosProperties properties = new AtengNacosProperties();

        AtomicReference<String> traceIdDuringRebind = new AtomicReference<>();
        doAnswer(invocation -> {
            traceIdDuringRebind.set(MDC.get(NacosConfigRefreshEngine.MDC_TRACE_ID_KEY));
            return null;
        }).when(rebinder).rebind();

        NacosConfigRefreshEngine engine = new NacosConfigRefreshEngine(
                environment,
                publisher,
                Collections.emptyList(),
                rebinder,
                properties
        );

        SafeConfigRefreshEvent event = engine.refreshConfig(Set.of("app.name"), "demo.yml", "DEFAULT_GROUP");

        assertThat(traceIdDuringRebind.get()).isNotBlank();
        assertThat(traceIdDuringRebind.get()).isEqualTo(event.getTraceId());
    }
}
