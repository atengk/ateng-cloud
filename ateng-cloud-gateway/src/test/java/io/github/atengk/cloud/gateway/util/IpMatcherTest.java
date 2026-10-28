package io.github.atengk.cloud.gateway.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IP 地址与 CIDR 掩码匹配工具单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("IP 匹配工具单元测试")
class IpMatcherTest {

    @Test
    @DisplayName("精确 IPv4 匹配测试")
    void testExactIpv4Match() {
        assertTrue(IpMatcher.matches("192.168.1.100", "192.168.1.100"));
        assertFalse(IpMatcher.matches("192.168.1.101", "192.168.1.100"));
    }

    @Test
    @DisplayName("IPv4 CIDR 子网掩码匹配测试")
    void testIpv4CidrMatch() {
        // /24 掩码测试
        assertTrue(IpMatcher.matches("192.168.1.1", "192.168.1.0/24"));
        assertTrue(IpMatcher.matches("192.168.1.254", "192.168.1.0/24"));
        assertFalse(IpMatcher.matches("192.168.2.1", "192.168.1.0/24"));

        // /16 掩码测试
        assertTrue(IpMatcher.matches("172.16.50.1", "172.16.0.0/16"));
        assertFalse(IpMatcher.matches("172.17.50.1", "172.16.0.0/16"));

        // /8 掩码测试
        assertTrue(IpMatcher.matches("10.250.1.99", "10.0.0.0/8"));
        assertFalse(IpMatcher.matches("11.0.0.1", "10.0.0.0/8"));

        // /32 单 IP 掩码测试
        assertTrue(IpMatcher.matches("10.0.0.1", "10.0.0.1/32"));
        assertFalse(IpMatcher.matches("10.0.0.2", "10.0.0.1/32"));
    }

    @Test
    @DisplayName("IPv6 精确与 CIDR 匹配测试")
    void testIpv6Match() {
        assertTrue(IpMatcher.matches("::1", "::1"));
        assertTrue(IpMatcher.matches("0:0:0:0:0:0:0:1", "::1"));

        // IPv6 CIDR
        assertTrue(IpMatcher.matches("fe80::1", "fe80::/10"));
        assertFalse(IpMatcher.matches("2001:db8::1", "fe80::/10"));
    }

    @Test
    @DisplayName("多规则集合匹配测试")
    void testMatchesAny() {
        List<String> rules = List.of("192.168.1.0/24", "10.0.0.0/8", "127.0.0.1");

        assertTrue(IpMatcher.matchesAny("192.168.1.50", rules));
        assertTrue(IpMatcher.matchesAny("10.1.2.3", rules));
        assertTrue(IpMatcher.matchesAny("127.0.0.1", rules));
        assertFalse(IpMatcher.matchesAny("172.16.1.1", rules));
    }

    @Test
    @DisplayName("边界与异常输入防御测试")
    void testEdgeCases() {
        assertFalse(IpMatcher.matches(null, "192.168.1.0/24"));
        assertFalse(IpMatcher.matches("192.168.1.1", null));
        assertFalse(IpMatcher.matches("invalid-ip", "192.168.1.0/24"));
        assertFalse(IpMatcher.matches("192.168.1.1", "invalid-rule/999"));
        assertFalse(IpMatcher.matchesAny("192.168.1.1", List.of()));
        assertFalse(IpMatcher.matchesAny("192.168.1.1", null));
    }
}
