package io.github.atengk.cloud.sample.traffic.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 订单履约全链路灰度调用轨迹视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class OrderFulfillmentVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务订单编号 (如 "ORD-20261103-9001")
     */
    private String orderNo;

    /**
     * 当前执行流生效的流量染色标签 (如 "v2"、"v3"、"none")
     */
    private String trafficTag;

    /**
     * 业务用户 ID
     */
    private String userId;

    /**
     * 多租户隔离租户 ID
     */
    private String tenantId;

    /**
     * 履约业务状态 (如 "DISPATCHED"、"FULFILLED")
     */
    private String deliveryStatus = "FULFILLED";

    /**
     * 响应业务状态 (如 "SUCCESS")
     */
    private String status = "SUCCESS";

    /**
     * 全链路微服务多跳节点与实例元数据快照轨迹 (如 ["Gateway[tag=v2]", "OrderService[inst=inst-order-gray, tag=v2]"])
     */
    private List<String> visitedNodes = new ArrayList<>();

    /**
     * 响应时间戳
     */
    private LocalDateTime timestamp = LocalDateTime.now();

    public OrderFulfillmentVO() {
    }

    public OrderFulfillmentVO(String trafficTag, String userId, String tenantId, String orderNo) {
        this.trafficTag = trafficTag;
        this.userId = userId;
        this.tenantId = tenantId;
        this.orderNo = orderNo;
    }

    /**
     * 追加访问节点到轨迹末尾
     *
     * @param node 节点描述
     */
    public void addVisitedNode(String node) {
        if (this.visitedNodes == null) {
            this.visitedNodes = new ArrayList<>();
        }
        this.visitedNodes.add(node);
    }

    /**
     * 将访问节点插入到轨迹首部 (用于逆向出栈组装链路)
     *
     * @param node 节点描述
     */
    public void prependVisitedNode(String node) {
        if (this.visitedNodes == null) {
            this.visitedNodes = new ArrayList<>();
        }
        this.visitedNodes.add(0, node);
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
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

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(String deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<String> getVisitedNodes() {
        return visitedNodes != null ? visitedNodes : Collections.emptyList();
    }

    public void setVisitedNodes(List<String> visitedNodes) {
        this.visitedNodes = visitedNodes != null ? visitedNodes : new ArrayList<>();
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "OrderFulfillmentVO{" +
                "orderNo='" + orderNo + '\'' +
                ", trafficTag='" + trafficTag + '\'' +
                ", userId='" + userId + '\'' +
                ", tenantId='" + tenantId + '\'' +
                ", deliveryStatus='" + deliveryStatus + '\'' +
                ", status='" + status + '\'' +
                ", visitedNodes=" + visitedNodes +
                ", timestamp=" + timestamp +
                '}';
    }
}
