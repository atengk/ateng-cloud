package io.github.atengk.cloud.gateway.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;

/**
 * IP 地址与 CIDR 子网掩码匹配工具类
 *
 * <p>支持单个 IPv4/IPv6 地址精确匹配以及标准 CIDR 网段（如 10.0.0.0/8、192.168.1.0/24、fe80::/10）位运算匹配，
 * 采用原生 JDK 网络协议与位掩码算法实现，零第三方依赖且具备高吞吐性能。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public final class IpMatcher {

    private static final Logger log = LoggerFactory.getLogger(IpMatcher.class);

    private IpMatcher() {
    }

    /**
     * 校验指定客户端 IP 是否匹配规则集合中的任意一项
     *
     * @param clientIp 客户端待校验真实 IP
     * @param rules    IP 规则列表（支持单个 IP 或 CIDR 掩码）
     * @return true 匹配任意一条规则；false 全部不匹配或规则为空
     */
    public static boolean matchesAny(String clientIp, Collection<String> rules) {
        if (!StringUtils.hasText(clientIp) || rules == null || rules.isEmpty()) {
            return false;
        }
        for (String rule : rules) {
            if (matches(clientIp, rule)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验指定客户端 IP 是否匹配单条 IP 规则
     *
     * @param clientIp 客户端待校验真实 IP
     * @param rule     单条 IP 规则（支持如 192.168.1.100 或 192.168.1.0/24）
     * @return true 匹配；false 不匹配
     */
    public static boolean matches(String clientIp, String rule) {
        if (!StringUtils.hasText(clientIp) || !StringUtils.hasText(rule)) {
            return false;
        }
        String trimmedRule = rule.trim();
        String trimmedIp = clientIp.trim();

        // 1. CIDR 掩码匹配 (如 192.168.1.0/24, 10.0.0.0/8)
        if (trimmedRule.contains("/")) {
            return matchCidr(trimmedIp, trimmedRule);
        }

        // 2. 单 IP 精确匹配
        try {
            InetAddress clientAddr = InetAddress.getByName(trimmedIp);
            InetAddress ruleAddr = InetAddress.getByName(trimmedRule);
            return clientAddr.equals(ruleAddr);
        } catch (UnknownHostException e) {
            log.debug("IP 格式解析失败: clientIp=[{}], rule=[{}]", trimmedIp, trimmedRule);
            return false;
        }
    }

    /**
     * 执行标准 CIDR 子网位掩码匹配
     *
     * @param clientIp 客户端 IP
     * @param cidrRule CIDR 网段规则 (如 192.168.0.0/16)
     * @return true 命中子网；false 未命中或格式非法
     */
    private static boolean matchCidr(String clientIp, String cidrRule) {
        int slashIndex = cidrRule.indexOf('/');
        if (slashIndex <= 0 || slashIndex == cidrRule.length() - 1) {
            return false;
        }
        String baseIp = cidrRule.substring(0, slashIndex).trim();
        String prefixStr = cidrRule.substring(slashIndex + 1).trim();

        int prefixLength;
        try {
            prefixLength = Integer.parseInt(prefixStr);
        } catch (NumberFormatException e) {
            log.warn("CIDR 掩码前缀无效: [{}]", cidrRule);
            return false;
        }

        try {
            InetAddress baseAddr = InetAddress.getByName(baseIp);
            InetAddress clientAddr = InetAddress.getByName(clientIp);

            byte[] baseBytes = baseAddr.getAddress();
            byte[] clientBytes = clientAddr.getAddress();

            // IPv4 与 IPv6 跨协议不匹配
            if (baseBytes.length != clientBytes.length) {
                return false;
            }

            int maxBits = baseBytes.length * 8;
            if (prefixLength < 0 || prefixLength > maxBits) {
                log.warn("CIDR 掩码长度超出协议边界: prefix=[{}], maxBits=[{}]", prefixLength, maxBits);
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remBits = prefixLength % 8;

            // 比较完整字节
            for (int i = 0; i < fullBytes; i++) {
                if (baseBytes[i] != clientBytes[i]) {
                    return false;
                }
            }

            // 比较剩余未对齐最高位 bits
            if (remBits > 0) {
                int mask = (0xFF << (8 - remBits)) & 0xFF;
                if ((baseBytes[fullBytes] & mask) != (clientBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (UnknownHostException e) {
            log.debug("CIDR IP 解析失败: clientIp=[{}], cidrRule=[{}]", clientIp, cidrRule);
            return false;
        }
    }
}
