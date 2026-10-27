package io.github.atengk.cloud.nacos.metrics;

import io.github.atengk.cloud.nacos.lifecycle.NacosInstanceLifecycleState;
import io.github.atengk.cloud.nacos.lifecycle.NacosLifecycleStateMachine;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nacos 基础设施与配置治理 Micrometer 全维监控标尺
 *
 * <p>向 Micrometer / Prometheus 导出 5 组核心指标：
 * <ul>
 *     <li>{@code nacos.grpc.connection.status} (Gauge)：Nacos gRPC 连接与配置服务连通状态（1: UP，0: DOWN）</li>
 *     <li>{@code nacos.config.refresh.total} (Counter)：配置动态热更触发总次数</li>
 *     <li>{@code nacos.config.refresh.failure} (Counter)：配置动态热更失败计数</li>
 *     <li>{@code nacos.instances.healthy} (Gauge)：当前微服务在 Nacos 中注册的健康实例数</li>
 *     <li>{@code nacos.lifecycle.state} (Gauge)：当前节点生命周期状态（1: UP，0: OUT_OF_SERVICE）</li>
 * </ul>
 * 具备 {@code MeterRegistry} 为空时的静默防御与无异常降级机制。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class NacosMetricsManager {

    private static final Logger log = LoggerFactory.getLogger(NacosMetricsManager.class);

    public static final String METRIC_GRPC_CONNECTION_STATUS = "nacos.grpc.connection.status";
    public static final String METRIC_GRPC_DISCONNECT_TOTAL = "nacos.grpc.disconnect.total";
    public static final String METRIC_CONFIG_REFRESH_TOTAL = "nacos.config.refresh.total";
    public static final String METRIC_CONFIG_REFRESH_FAILURE = "nacos.config.refresh.failure";
    public static final String METRIC_INSTANCES_HEALTHY = "nacos.instances.healthy";
    public static final String METRIC_LIFECYCLE_STATE = "nacos.lifecycle.state";

    private final MeterRegistry meterRegistry;
    private final ObjectProvider<NacosTemplate> nacosTemplateProvider;
    private final ObjectProvider<NacosLifecycleStateMachine> stateMachineProvider;
    private final String serviceName;
    private final AtomicBoolean grpcConnected = new AtomicBoolean(true);

    private Counter grpcDisconnectCounter;
    private Counter refreshTotalCounter;
    private Counter refreshFailureCounter;

    public NacosMetricsManager(@Nullable MeterRegistry meterRegistry,
                               ObjectProvider<NacosTemplate> nacosTemplateProvider,
                               ObjectProvider<NacosLifecycleStateMachine> stateMachineProvider,
                               Environment environment) {
        this.meterRegistry = meterRegistry;
        this.nacosTemplateProvider = nacosTemplateProvider;
        this.stateMachineProvider = stateMachineProvider;
        this.serviceName = resolveServiceName(environment);

        if (this.meterRegistry != null) {
            registerMetrics();
        } else {
            log.debug("MeterRegistry not present. Nacos Micrometer metrics registration skipped.");
        }
    }

    private String resolveServiceName(Environment environment) {
        if (environment == null) {
            return "unknown";
        }
        String name = environment.getProperty("spring.application.name");
        return StringUtils.hasText(name) ? name : "unknown";
    }

    private void registerMetrics() {
        // 1. nacos.grpc.connection.status (Gauge)
        Gauge.builder(METRIC_GRPC_CONNECTION_STATUS, this, NacosMetricsManager::getGrpcConnectionStatus)
                .description("Nacos gRPC connection and config server status (1: UP/connected, 0: DOWN/disconnected)")
                .tag("service", serviceName)
                .register(meterRegistry);

        // 1.1 nacos.grpc.disconnect.total (Counter)
        this.grpcDisconnectCounter = Counter.builder(METRIC_GRPC_DISCONNECT_TOTAL)
                .description("Total count of Nacos gRPC connection disconnections (network flappings)")
                .tag("service", serviceName)
                .register(meterRegistry);

        // 2. nacos.config.refresh.total (Counter)
        this.refreshTotalCounter = Counter.builder(METRIC_CONFIG_REFRESH_TOTAL)
                .description("Total count of Nacos dynamic config refresh triggers")
                .tag("service", serviceName)
                .register(meterRegistry);

        // 3. nacos.config.refresh.failure (Counter)
        this.refreshFailureCounter = Counter.builder(METRIC_CONFIG_REFRESH_FAILURE)
                .description("Total count of Nacos dynamic config refresh failures")
                .tag("service", serviceName)
                .register(meterRegistry);

        // 4. nacos.instances.healthy (Gauge)
        Gauge.builder(METRIC_INSTANCES_HEALTHY, this, NacosMetricsManager::getHealthyInstancesCount)
                .description("Number of healthy instances registered in Nacos for current service")
                .tag("service", serviceName)
                .register(meterRegistry);

        // 5. nacos.lifecycle.state (Gauge)
        Gauge.builder(METRIC_LIFECYCLE_STATE, this, NacosMetricsManager::getLifecycleState)
                .description("Nacos instance graceful lifecycle state (1: UP, 0: OUT_OF_SERVICE)")
                .tag("service", serviceName)
                .register(meterRegistry);

        log.info("ateng-cloud Nacos Micrometer metrics registered successfully: service={}", serviceName);
    }

    /**
     * 读取 Nacos gRPC 与配置服务连接状态标尺
     */
    public double getGrpcConnectionStatus() {
        if (!grpcConnected.get()) {
            return 0.0;
        }
        try {
            NacosTemplate template = nacosTemplateProvider.getIfAvailable();
            if (template != null) {
                if (template.isConfigAvailable()) {
                    String status = template.getConfigServerStatus();
                    return "UP".equalsIgnoreCase(status) ? 1.0 : 0.0;
                }
                return template.isDiscoveryAvailable() ? 1.0 : 0.0;
            }
        } catch (Exception ex) {
            log.debug("Error probing Nacos gRPC connection status: {}", ex.getMessage());
        }
        return 0.0;
    }

    /**
     * 记录一次 gRPC 长连接断开事件
     *
     * @param moduleType    模块类型 (NAMING / CONFIG)
     * @param serverAddress 远端服务地址
     */
    public void recordGrpcDisconnect(String moduleType, @Nullable String serverAddress) {
        grpcConnected.set(false);
        if (grpcDisconnectCounter != null) {
            grpcDisconnectCounter.increment();
        }
        log.warn("[ateng-cloud-nacos] Recorded Nacos gRPC connection disconnect [module: {}, server: {}]",
                moduleType, serverAddress);
    }

    /**
     * 记录一次 gRPC 长连接重连恢复事件
     *
     * @param moduleType    模块类型 (NAMING / CONFIG)
     * @param serverAddress 远端服务地址
     */
    public void recordGrpcConnect(String moduleType, @Nullable String serverAddress) {
        grpcConnected.set(true);
        log.info("[ateng-cloud-nacos] Recorded Nacos gRPC connection restored [module: {}, server: {}]",
                moduleType, serverAddress);
    }

    /**
     * 获取断连抖动总计数
     */
    public double getGrpcDisconnectCount() {
        return grpcDisconnectCounter != null ? grpcDisconnectCounter.count() : 0.0;
    }

    /**
     * 读取当前微服务在 Nacos 中的健康实例数量标尺
     */
    public double getHealthyInstancesCount() {
        try {
            NacosTemplate template = nacosTemplateProvider.getIfAvailable();
            if (template != null && template.isDiscoveryAvailable()) {
                List<?> healthyInstances = template.getHealthyInstances(serviceName);
                return healthyInstances != null ? healthyInstances.size() : 0.0;
            }
        } catch (Exception ex) {
            log.debug("Error querying Nacos healthy instances count for service '{}': {}", serviceName, ex.getMessage());
        }
        return 0.0;
    }

    /**
     * 读取当前节点生命周期状态标尺（UP=1.0，OUT_OF_SERVICE=0.0）
     */
    public double getLifecycleState() {
        try {
            NacosLifecycleStateMachine stateMachine = stateMachineProvider.getIfAvailable();
            if (stateMachine != null) {
                return stateMachine.getState() == NacosInstanceLifecycleState.UP ? 1.0 : 0.0;
            }
        } catch (Exception ex) {
            log.debug("Error reading Nacos lifecycle state: {}", ex.getMessage());
        }
        return 0.0;
    }

    /**
     * 记录一次配置热更成功事件
     *
     * @param durationMs       热更耗时（毫秒）
     * @param changedKeysCount 变更键数量
     */
    public void recordRefreshSuccess(long durationMs, int changedKeysCount) {
        if (refreshTotalCounter != null) {
            refreshTotalCounter.increment();
        }
        log.debug("Recorded Nacos config refresh success [duration: {}ms, changedKeysCount: {}]", durationMs, changedKeysCount);
    }

    /**
     * 记录一次配置热更失败事件
     *
     * @param durationMs 热更耗时（毫秒）
     * @param error      错误信息
     */
    public void recordRefreshFailure(long durationMs, @Nullable String error) {
        if (refreshTotalCounter != null) {
            refreshTotalCounter.increment();
        }
        if (refreshFailureCounter != null) {
            refreshFailureCounter.increment();
        }
        log.warn("Recorded Nacos config refresh failure [duration: {}ms, error: {}]", durationMs, error);
    }

    public boolean isEnabled() {
        return meterRegistry != null;
    }
}
