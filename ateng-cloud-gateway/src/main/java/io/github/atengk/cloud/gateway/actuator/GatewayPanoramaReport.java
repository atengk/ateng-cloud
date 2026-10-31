package io.github.atengk.cloud.gateway.actuator;

import java.io.Serializable;

/**
 * 响应式 API 网关全景健康与运维诊断结构化报告对象
 *
 * <p>微服务专属架构铁律落地：
 * <ul>
 *     <li><b>四维只读聚合</b>：聚合动态双轨路由、金丝雀灰度负载均衡、零信任边缘安全与开放接口签名防卫；</li>
 *     <li><b>零泄漏机密脱敏</b>：IP 黑白名单与敏感标头仅输出规则总数，绝不向外暴露内部 CIDR 拓扑与敏感密钥；</li>
 *     <li><b>非阻塞无锁快照</b>：指标 100% 提取自内存原子快照，微秒级极速响应，零外部网络 IO 阻塞。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public record GatewayPanoramaReport(
        String status,
        String gatewayId,
        long timestamp,
        DynamicRouteReport dynamicRoute,
        GrayLoadBalancerReport grayLoadBalancer,
        EdgeSecurityReport edgeSecurity,
        SignGuardReport signGuard
) implements Serializable {

    /**
     * 动态双轨路由诊断数据
     */
    public record DynamicRouteReport(
            boolean enabled,
            boolean dualTrackEnabled,
            int totalRouteCount,
            NacosRouteReport nacosRoute,
            MainConfigRouteReport mainConfigRoute
    ) implements Serializable {}

    /**
     * Nacos 独立动态路由快照数据
     */
    public record NacosRouteReport(
            boolean enabled,
            String dataId,
            String group,
            int routeCount,
            boolean snapshotHealthy,
            Long lastRefreshTime,
            Boolean lastRefreshSuccess,
            String lastRefreshError
    ) implements Serializable {}

    /**
     * 主配置文件动态路由快照数据
     */
    public record MainConfigRouteReport(
            int routeCount,
            int defaultFilterCount,
            boolean snapshotHealthy,
            Long lastRefreshTime,
            Boolean lastRefreshSuccess,
            String lastRefreshError
    ) implements Serializable {}

    /**
     * 全链路流量染色与金丝雀灰度负载均衡诊断数据
     */
    public record GrayLoadBalancerReport(
            boolean enabled,
            boolean fallbackToDefault,
            String cacheTtl,
            String zone
    ) implements Serializable {}

    /**
     * 零信任边缘防卫与安全清洗诊断数据
     */
    public record EdgeSecurityReport(
            boolean enabled,
            boolean saTokenEnabled,
            boolean pathValidationEnabled,
            boolean headerSanitizeEnabled,
            boolean clientIpEnabled,
            boolean ipFilterEnabled,
            int ipBlacklistCount,
            int ipWhitelistCount,
            int sanitizeHeadersCount
    ) implements Serializable {}

    /**
     * 开放接口 API 签名防篡改与 Nonce 防重放诊断数据
     */
    public record SignGuardReport(
            boolean enabled,
            boolean bodyEnabled,
            String timestampThreshold,
            int includePathsCount,
            int excludePathsCount
    ) implements Serializable {}
}
