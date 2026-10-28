package io.github.atengk.cloud.sample.nacos.model;

/**
 * Nacos 长连接事件状态视图对象
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class ConnectionEventVO {

    private int eventCount;
    private String moduleType;
    private String status;
    private String serverAddress;

    public ConnectionEventVO() {
    }

    public ConnectionEventVO(int eventCount, String moduleType, String status, String serverAddress) {
        this.eventCount = eventCount;
        this.moduleType = moduleType;
        this.status = status;
        this.serverAddress = serverAddress;
    }

    public int getEventCount() {
        return eventCount;
    }

    public void setEventCount(int eventCount) {
        this.eventCount = eventCount;
    }

    public String getModuleType() {
        return moduleType;
    }

    public void setModuleType(String moduleType) {
        this.moduleType = moduleType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
    }
}
