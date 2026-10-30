package io.github.atengk.cloud.nacos.network;

import java.io.Serializable;
import java.util.Objects;

/**
 * Nacos 注册网络接口与 IP 探测结果
 *
 * <p>封装侦测绑定的物理网卡名称、选中的宿主 IP 地址及是否为回环地址标记，
 * 用于启动自检报告、日志审计与高危回环告警预警。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class NetworkAddressResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 侦测到的宿主 IPv4 地址
     */
    private final String ip;

    /**
     * 绑定的物理网卡名称（如 eth0、wlan0 等）
     */
    private final String networkInterface;

    /**
     * 是否为 127.0.0.1 等回环地址
     */
    private final boolean loopback;

    public NetworkAddressResult(String ip, String networkInterface, boolean loopback) {
        this.ip = ip != null ? ip : "127.0.0.1";
        this.networkInterface = networkInterface != null ? networkInterface : "unknown";
        this.loopback = loopback;
    }

    public String getIp() {
        return ip;
    }

    public String getNetworkInterface() {
        return networkInterface;
    }

    public boolean isLoopback() {
        return loopback;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NetworkAddressResult that = (NetworkAddressResult) o;
        return loopback == that.loopback &&
                Objects.equals(ip, that.ip) &&
                Objects.equals(networkInterface, that.networkInterface);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip, networkInterface, loopback);
    }

    @Override
    public String toString() {
        return "NetworkAddressResult{" +
                "ip='" + ip + '\'' +
                ", networkInterface='" + networkInterface + '\'' +
                ", loopback=" + loopback +
                '}';
    }
}
