package io.github.atengk.cloud.gateway.spi;

import io.github.atengk.cloud.gateway.util.IpMatcher;

import java.util.Collections;
import java.util.List;

/**
 * IP 黑白名单规则提供者 SPI 扩展点
 *
 * <p>允许业务端无缝扩展基于 Redis、Nacos 或自研安全运维系统的动态 IP 防护规则，
 * 实现毫秒级动态拉黑与白名单受控放行。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public interface IpFilterProvider {

    /**
     * 获取当前生效的 IP 黑名单列表 (支持单个 IP 与 CIDR 掩码)
     *
     * @return IP 黑名单列表，无黑名单时返回空列表
     */
    default List<String> getBlacklist() {
        return Collections.emptyList();
    }

    /**
     * 获取当前生效的 IP 白名单列表 (支持单个 IP 与 CIDR 掩码)
     *
     * @return IP 白名单列表，未启用白名单时返回空列表
     */
    default List<String> getWhitelist() {
        return Collections.emptyList();
    }

    /**
     * 校验指定客户端 IP 是否允许通过网关
     *
     * @param clientIp 客户端权威真实 IP
     * @return true 允许放行；false 阻断拦截 (命中黑名单或未在白名单)
     */
    default boolean isAllowed(String clientIp) {
        List<String> blacklist = getBlacklist();
        if (blacklist != null && !blacklist.isEmpty() && IpMatcher.matchesAny(clientIp, blacklist)) {
            return false;
        }
        List<String> whitelist = getWhitelist();
        if (whitelist != null && !whitelist.isEmpty() && !IpMatcher.matchesAny(clientIp, whitelist)) {
            return false;
        }
        return true;
    }
}
