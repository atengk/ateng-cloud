package io.github.atengk.cloud.sample.nacos.model;

import com.alibaba.nacos.api.naming.pojo.Instance;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * Nacos 服务实例视图对象
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class InstanceVO implements Serializable {

    private String serviceName;
    private String ip;
    private int port;
    private String clusterName;
    private double weight;
    private boolean healthy;
    private boolean enabled;
    private Map<String, String> metadata;

    public InstanceVO() {
    }

    public static InstanceVO fromInstance(Instance instance) {
        if (instance == null) {
            return null;
        }
        InstanceVO vo = new InstanceVO();
        vo.setServiceName(instance.getServiceName());
        vo.setIp(instance.getIp());
        vo.setPort(instance.getPort());
        vo.setClusterName(instance.getClusterName());
        vo.setWeight(instance.getWeight());
        vo.setHealthy(instance.isHealthy());
        vo.setEnabled(instance.isEnabled());
        vo.setMetadata(instance.getMetadata() != null ? instance.getMetadata() : Collections.emptyMap());
        return vo;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
}
