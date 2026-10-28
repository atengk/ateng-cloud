package io.github.atengk.cloud.nacos.event;

import com.alibaba.nacos.common.remote.client.Connection;
import com.alibaba.nacos.common.remote.client.ConnectionEventListener;
import io.github.atengk.cloud.nacos.metrics.NacosMetricsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;

/**
 * Nacos 原生 gRPC 长连接事件监听器实现
 *
 * <p>捕获 Nacos 底层 RPC Client 状态流转，向 Spring 领域事件总线广播 {@link NacosConnectionEvent}，
 * 并联动 {@link NacosMetricsManager} 记录指标与告警。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class NacosConnectionEventListener implements ConnectionEventListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NacosConnectionEventListener.class);

    private final String clientName;
    private final NacosConnectionEvent.ModuleType moduleType;
    private final ApplicationEventPublisher eventPublisher;
    private final NacosMetricsManager metricsManager;
    private volatile boolean closed = false;

    public NacosConnectionEventListener(String clientName,
                                        ApplicationEventPublisher eventPublisher,
                                        @Nullable NacosMetricsManager metricsManager) {
        this.clientName = clientName != null ? clientName : "default";
        this.moduleType = resolveModuleType(this.clientName);
        this.eventPublisher = eventPublisher;
        this.metricsManager = metricsManager;
    }

    /**
     * 关闭并注销当前监听器，阻断后续事件传播
     */
    @Override
    public void close() {
        this.closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void onConnected(Connection connection) {
        if (closed) {
            return;
        }
        String serverAddress = resolveServerAddress(connection);
        log.info("[ateng-cloud-nacos] Nacos gRPC connection CONNECTED for module '{}' (client: '{}', server: '{}')",
                moduleType, clientName, serverAddress);

        if (metricsManager != null) {
            metricsManager.recordGrpcConnect(moduleType, serverAddress);
        }

        publishEvent(NacosConnectionEvent.ConnectionStatus.CONNECTED, serverAddress);
    }

    @Override
    public void onDisConnect(Connection connection) {
        if (closed) {
            return;
        }
        String serverAddress = resolveServerAddress(connection);
        log.warn("[ateng-cloud-nacos] Nacos gRPC connection DISCONNECTED for module '{}' (client: '{}', server: '{}'). Network flapping or server unavailable.",
                moduleType, clientName, serverAddress);

        if (metricsManager != null) {
            metricsManager.recordGrpcDisconnect(moduleType, serverAddress);
        }

        publishEvent(NacosConnectionEvent.ConnectionStatus.DISCONNECTED, serverAddress);
    }

    private void publishEvent(NacosConnectionEvent.ConnectionStatus status, String serverAddress) {
        if (eventPublisher == null) {
            return;
        }
        try {
            NacosConnectionEvent event = new NacosConnectionEvent(this, moduleType, status, serverAddress, clientName);
            eventPublisher.publishEvent(event);
        } catch (Exception ex) {
            log.warn("[ateng-cloud-nacos] Failed to publish NacosConnectionEvent for module '{}': {}",
                    moduleType, ex.getMessage());
        }
    }

    public static String resolveServerAddress(Connection connection) {
        if (connection == null) {
            return "unknown";
        }
        String connId = connection.getConnectionId();
        if (connId != null && !connId.isBlank()) {
            int idx = connId.indexOf('#');
            return idx > 0 ? connId.substring(0, idx) : connId;
        }
        return "unknown";
    }

    public static NacosConnectionEvent.ModuleType resolveModuleType(String name) {
        if (name == null) {
            return NacosConnectionEvent.ModuleType.UNKNOWN;
        }
        String lower = name.toLowerCase();
        if (lower.contains("naming")) {
            return NacosConnectionEvent.ModuleType.NAMING;
        }
        if (lower.contains("config")) {
            return NacosConnectionEvent.ModuleType.CONFIG;
        }
        return NacosConnectionEvent.ModuleType.UNKNOWN;
    }

    public String getClientName() {
        return clientName;
    }

    public NacosConnectionEvent.ModuleType getModuleType() {
        return moduleType;
    }
}
