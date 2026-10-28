package io.github.atengk.cloud.dubbo.properties;

/**
 * Dubbo 协议基线配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengDubboProtocolProperties {

    /**
     * 默认通信协议名称，默认 "tri" (Triple 协议)；亦完全支持经典 TCP "dubbo" 协议
     */
    private String name = "tri";

    /**
     * 默认服务暴露端口，默认 50051 (Triple / gRPC 标准端口；若使用 dubbo 协议推荐 20880)
     */
    private Integer port = 50051;

    /**
     * 默认序列化算法，默认 "fastjson2" (Fastjson2 JSONB 二进制模式)
     */
    private String serialization = "fastjson2";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getSerialization() {
        return serialization;
    }

    public void setSerialization(String serialization) {
        this.serialization = serialization;
    }
}
