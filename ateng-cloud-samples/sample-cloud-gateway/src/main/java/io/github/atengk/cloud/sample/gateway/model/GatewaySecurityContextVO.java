package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;
import java.util.Map;

/**
 * 网关受信任出站安全上下文视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public record GatewaySecurityContextVO(
        String traceId,
        String userId,
        String tenantId,
        String sameToken,
        String trafficTag,
        String clientIp,
        Map<String, String> rawHeaders
) implements Serializable {
}
