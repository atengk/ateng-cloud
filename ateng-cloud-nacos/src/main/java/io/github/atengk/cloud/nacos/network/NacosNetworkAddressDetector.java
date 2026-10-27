package io.github.atengk.cloud.nacos.network;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Nacos 注册网络接口与 IP 智能侦测器
 *
 * <p>负责在多网卡与容器环境下清洗桥接/虚拟网卡（如 docker0、cni0、flannel 等），
 * 并优先绑定符合私网规划与首选网段的可路由真实 IP。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class NacosNetworkAddressDetector {

    private static final Logger log = LoggerFactory.getLogger(NacosNetworkAddressDetector.class);

    private static final String LOOPBACK_IP = "127.0.0.1";

    /**
     * 根据网络配置策略智能解析并返回当前最适合注册至 Nacos 的 IP 地址
     *
     * @param networkProps 网络配置属性
     * @return 侦测到的 IPv4 地址字符串
     * @throws IllegalStateException 当配置了 failOnLoopback 且仅找到 127.0.0.1 时抛出
     */
    public String detectIp(AtengNacosProperties.NetworkProperties networkProps) {
        if (networkProps == null) {
            networkProps = new AtengNacosProperties.NetworkProperties();
        }

        // 1. 若显式指定了物理网卡名，强制优先从该网卡获取
        if (StringUtils.hasText(networkProps.getNetworkInterface())) {
            String specifiedInterface = networkProps.getNetworkInterface().trim();
            log.info("Nacos network interface explicitly specified: '{}'", specifiedInterface);
            String ip = getIpFromSpecifiedInterface(specifiedInterface);
            if (StringUtils.hasText(ip)) {
                return ip;
            }
            log.warn("Specified network interface '{}' not found or has no valid IPv4 address. Falling back to auto-detection.", specifiedInterface);
        }

        // 2. 遍历宿主机/容器所有网卡并执行清洗过滤
        List<InetAddress> candidateAddresses = getValidCandidateAddresses(networkProps.getIgnoredInterfaces());

        // 3. 匹配首选网段 (Preferred Networks)
        List<String> preferred = networkProps.getPreferredNetworks();
        if (preferred != null && !preferred.isEmpty()) {
            for (String prefix : preferred) {
                if (StringUtils.hasText(prefix)) {
                    for (InetAddress addr : candidateAddresses) {
                        String host = addr.getHostAddress();
                        if (host.startsWith(prefix.trim())) {
                            log.info("Nacos registered IP matched preferred network prefix '{}': {}", prefix, host);
                            return host;
                        }
                    }
                }
            }
        }

        // 4. 优先选择私有内网网段 (Site-Local Address: 10.*, 172.16~31.*, 192.168.*)
        for (InetAddress addr : candidateAddresses) {
            if (addr.isSiteLocalAddress()) {
                String host = addr.getHostAddress();
                log.info("Nacos registered IP selected site-local address: {}", host);
                return host;
            }
        }

        // 5. 次选其他非回环 IPv4
        if (!candidateAddresses.isEmpty()) {
            String host = candidateAddresses.get(0).getHostAddress();
            log.info("Nacos registered IP selected public/other address: {}", host);
            return host;
        }

        // 6. 兜底回环地址与 Fail-Fast 校验
        if (networkProps.isFailOnLoopback()) {
            throw new IllegalStateException("Nacos network address detection failed: only loopback address (127.0.0.1) detected, and fail-on-loopback is enabled.");
        }

        log.warn("Nacos network address detection fallback to loopback address (127.0.0.1). This instance may not be reachable remotely.");
        return LOOPBACK_IP;
    }

    /**
     * 判断指定网卡名称是否符合忽略黑名单
     */
    public boolean isIgnoredInterface(String interfaceName, List<String> ignoredPatterns) {
        if (!StringUtils.hasText(interfaceName) || ignoredPatterns == null || ignoredPatterns.isEmpty()) {
            return false;
        }
        for (String patternStr : ignoredPatterns) {
            if (StringUtils.hasText(patternStr)) {
                try {
                    Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
                    if (pattern.matcher(interfaceName).find() || pattern.matcher(interfaceName).matches()) {
                        return true;
                    }
                } catch (Exception e) {
                    // 若正则语法有误则做简单的包含判断
                    if (interfaceName.toLowerCase().contains(patternStr.toLowerCase())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String getIpFromSpecifiedInterface(String interfaceName) {
        try {
            NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
            if (networkInterface != null && networkInterface.isUp()) {
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Error reading specified network interface '{}': {}", interfaceName, ex.getMessage());
        }
        return null;
    }

    private List<InetAddress> getValidCandidateAddresses(List<String> ignoredPatterns) {
        List<InetAddress> candidates = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return candidates;
            }

            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isPointToPoint() || networkInterface.isVirtual()) {
                    continue;
                }

                String name = networkInterface.getName();
                String displayName = networkInterface.getDisplayName();
                if (isIgnoredInterface(name, ignoredPatterns) || isIgnoredInterface(displayName, ignoredPatterns)) {
                    log.debug("Ignored virtual/bridge network interface: name='{}', display='{}'", name, displayName);
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        candidates.add(addr);
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Failed to enumerate network interfaces: {}", ex.getMessage(), ex);
        }
        return candidates;
    }
}
