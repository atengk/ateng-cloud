package io.github.atengk.cloud.sample.traffic.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 全链路微服务多跳灰度调用轨迹追踪报告模型
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficTraceReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 贯穿全链路的流量染色标签
     */
    private String trafficTag;

    /**
     * 当前业务用户 ID
     */
    private String userId;

    /**
     * 当前业务租户 ID
     */
    private String tenantId;

    /**
     * 全调用链路途经节点列表 (如 ["Gateway", "ServiceA[v2]", "ServiceB[base]", "ServiceC[v2]"])
     */
    private List<String> visitedNodes = new ArrayList<>();

    /**
     * 响应状态 (如 SUCCESS)
     */
    private String status = "SUCCESS";

    public TrafficTraceReport() {
    }

    public TrafficTraceReport(String trafficTag, String userId, String tenantId) {
        this.trafficTag = trafficTag;
        this.userId = userId;
        this.tenantId = tenantId;
    }

    public void addVisitedNode(String node) {
        if (this.visitedNodes == null) {
            this.visitedNodes = new ArrayList<>();
        }
        this.visitedNodes.add(node);
    }

    public void prependVisitedNode(String node) {
        if (this.visitedNodes == null) {
            this.visitedNodes = new ArrayList<>();
        }
        this.visitedNodes.add(0, node);
    }

    public String getTrafficTag() {
        return trafficTag;
    }

    public void setTrafficTag(String trafficTag) {
        this.trafficTag = trafficTag;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public List<String> getVisitedNodes() {
        return visitedNodes;
    }

    public void setVisitedNodes(List<String> visitedNodes) {
        this.visitedNodes = visitedNodes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "TrafficTraceReport{" +
                "trafficTag='" + trafficTag + '\'' +
                ", userId='" + userId + '\'' +
                ", tenantId='" + tenantId + '\'' +
                ", visitedNodes=" + visitedNodes +
                ", status='" + status + '\'' +
                '}';
    }
}
