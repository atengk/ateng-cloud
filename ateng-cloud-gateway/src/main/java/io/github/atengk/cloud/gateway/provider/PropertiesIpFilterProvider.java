package io.github.atengk.cloud.gateway.provider;

import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityProperties;
import io.github.atengk.cloud.gateway.spi.IpFilterProvider;

import java.util.Collections;
import java.util.List;

/**
 * 基于本地配置属性的默认 IP 黑白名单规则提供者
 *
 * <p>读取 {@code ateng.cloud.gateway.security.ip-blacklist} 与
 * {@code ateng.cloud.gateway.security.ip-whitelist}，作为默认 IP 拦截规则源。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class PropertiesIpFilterProvider implements IpFilterProvider {

    private final AtengGatewaySecurityProperties securityProperties;

    public PropertiesIpFilterProvider(AtengGatewaySecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public List<String> getBlacklist() {
        if (securityProperties == null || securityProperties.getIpBlacklist() == null) {
            return Collections.emptyList();
        }
        return securityProperties.getIpBlacklist();
    }

    @Override
    public List<String> getWhitelist() {
        if (securityProperties == null || securityProperties.getIpWhitelist() == null) {
            return Collections.emptyList();
        }
        return securityProperties.getIpWhitelist();
    }
}
