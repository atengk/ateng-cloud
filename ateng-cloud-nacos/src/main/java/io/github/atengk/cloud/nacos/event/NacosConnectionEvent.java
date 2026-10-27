package io.github.atengk.cloud.nacos.event;

import org.springframework.context.ApplicationEvent;

/**
 * Nacos 底层 gRPC 长连接生命周期状态变更领域事件
 *
 * <p>当 Nacos 命名服务（Naming）或配置中心（Config）的 gRPC 长连接发生闪断、
 * 断线重连或服务端主从倒换时，向 Spring 应用上下文广播本事件。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class NacosConnectionEvent extends ApplicationEvent {

    private final ModuleType moduleType;
    private final ConnectionStatus status;
    private final String serverAddress;
    private final String clientName;

    public NacosConnectionEvent(Object source,
                                ModuleType moduleType,
                                ConnectionStatus status,
                                String serverAddress,
                                String clientName) {
        super(source);
        this.moduleType = moduleType != null ? moduleType : ModuleType.UNKNOWN;
        this.status = status != null ? status : ConnectionStatus.DISCONNECTED;
        this.serverAddress = serverAddress != null ? serverAddress : "unknown";
        this.clientName = clientName != null ? clientName : "unknown";
    }

    public ModuleType getModuleType() {
        return moduleType;
    }

    public ConnectionStatus getStatus() {
        return status;
    }

    public boolean isConnected() {
        return status == ConnectionStatus.CONNECTED;
    }

    public boolean isDisconnected() {
        return status == ConnectionStatus.DISCONNECTED;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public String getClientName() {
        return clientName;
    }

    @Override
    public String toString() {
        return "NacosConnectionEvent{" +
                "moduleType=" + moduleType +
                ", status=" + status +
                ", serverAddress='" + serverAddress + '\'' +
                ", clientName='" + clientName + '\'' +
                ", timestamp=" + getTimestamp() +
                '}';
    }

    /**
     * 连接所属功能模块类型
     */
    public enum ModuleType {
        /**
         * 服务注册与发现客户端 (Naming)
         */
        NAMING,
        /**
         * 动态配置中心客户端 (Config)
         */
        CONFIG,
        /**
         * 未知或通用 RPC 客户端
         */
        UNKNOWN
    }

    /**
     * 长连接通信状态
     */
    public enum ConnectionStatus {
        /**
         * 连接已建立或重连成功
         */
        CONNECTED,
        /**
         * 连接断开或网络异常闪断
         */
        DISCONNECTED
    }
}
