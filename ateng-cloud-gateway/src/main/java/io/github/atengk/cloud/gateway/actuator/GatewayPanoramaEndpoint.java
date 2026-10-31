package io.github.atengk.cloud.gateway.actuator;

import io.github.atengk.cloud.gateway.properties.AtengGatewayDynamicRouteProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
import io.github.atengk.cloud.gateway.route.GatewayRouteRefreshListener;
import io.github.atengk.cloud.gateway.route.NacosRouteDefinitionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * 网关全景健康与动态路由只读运维诊断端点
 *
 * <p>端点路径：{@code /actuator/atengGateway} 与 {@code /actuator/ateng-gateway}。
 * 遵循非阻塞无锁架构，所有指标读取自内存原子快照，微秒级极速响应，零外部网络 IO。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Endpoint(id = "atengGateway")
public class GatewayPanoramaEndpoint {

    private final ObjectProvider<AtengGatewayProperties> propertiesProvider;
    private final ObjectProvider<NacosRouteDefinitionRepository> nacosRepoProvider;
    private final ObjectProvider<GatewayRouteRefreshListener> routeRefreshListenerProvider;
    private final Environment environment;

    public GatewayPanoramaEndpoint(
            ObjectProvider<AtengGatewayProperties> propertiesProvider,
            ObjectProvider<NacosRouteDefinitionRepository> nacosRepoProvider,
            ObjectProvider<GatewayRouteRefreshListener> routeRefreshListenerProvider,
            Environment environment) {
        this.propertiesProvider = propertiesProvider;
        this.nacosRepoProvider = nacosRepoProvider;
        this.routeRefreshListenerProvider = routeRefreshListenerProvider;
        this.environment = environment;
    }

    @ReadOperation
    public GatewayPanoramaReport report() {
        AtengGatewayProperties props = propertiesProvider != null ? propertiesProvider.getIfAvailable() : null;
        if (props == null) {
            props = new AtengGatewayProperties();
        }

        NacosRouteDefinitionRepository nacosRepo = nacosRepoProvider != null ? nacosRepoProvider.getIfAvailable() : null;
        GatewayRouteRefreshListener refreshListener = routeRefreshListenerProvider != null ? routeRefreshListenerProvider.getIfAvailable() : null;

        String gatewayId = environment != null
                ? environment.getProperty("spring.application.name", "ateng-gateway")
                : "ateng-gateway";

        // 1. 动态双轨路由诊断
        AtengGatewayDynamicRouteProperties routeProps = props.getDynamicRoute() != null
                ? props.getDynamicRoute() : new AtengGatewayDynamicRouteProperties();

        int nacosRouteCount = nacosRepo != null ? nacosRepo.getRouteCount() : 0;
        boolean nacosHealthy = nacosRepo == null || nacosRepo.isSnapshotHealthy();
        Long nacosRefreshTime = nacosRepo != null ? nacosRepo.getLastRefreshTime() : null;
        Boolean nacosRefreshSuccess = nacosRepo != null ? nacosRepo.getLastRefreshSuccess() : null;
        String nacosRefreshError = nacosRepo != null ? nacosRepo.getLastRefreshError() : null;

        GatewayPanoramaReport.NacosRouteReport nacosReport = new GatewayPanoramaReport.NacosRouteReport(
                routeProps.isEnabled(),
                routeProps.getDataId(),
                routeProps.getGroup(),
                nacosRouteCount,
                nacosHealthy,
                nacosRefreshTime,
                nacosRefreshSuccess,
                nacosRefreshError
        );

        int mainRouteCount = refreshListener != null ? refreshListener.getRouteCount() : 0;
        int mainFilterCount = refreshListener != null ? refreshListener.getDefaultFilterCount() : 0;
        boolean mainHealthy = refreshListener == null || refreshListener.isSnapshotHealthy();
        Long mainRefreshTime = refreshListener != null ? refreshListener.getLastRefreshTime() : null;
        Boolean mainRefreshSuccess = refreshListener != null ? refreshListener.getLastRefreshSuccess() : null;
        String mainRefreshError = refreshListener != null ? refreshListener.getLastRefreshError() : null;

        GatewayPanoramaReport.MainConfigRouteReport mainReport = new GatewayPanoramaReport.MainConfigRouteReport(
                mainRouteCount,
                mainFilterCount,
                mainHealthy,
                mainRefreshTime,
                mainRefreshSuccess,
                mainRefreshError
        );

        // 计算双轨路由去重仲裁后的有效总路由数 (Nacos 路由覆盖同名主配置路由)
        Set<String> uniqueRouteIds = new HashSet<>();
        if (nacosRepo != null) {
            uniqueRouteIds.addAll(nacosRepo.getRoutesSnapshot().keySet());
        }
        if (refreshListener != null) {
            for (RouteDefinition route : refreshListener.getLastKnownGoodRoutes()) {
                if (route != null && StringUtils.hasText(route.getId())) {
                    uniqueRouteIds.add(route.getId());
                }
            }
        }
        int totalRouteCount = uniqueRouteIds.size();
        boolean dualTrackEnabled = routeProps.isEnabled() && nacosRepo != null && refreshListener != null;

        GatewayPanoramaReport.DynamicRouteReport dynamicRouteReport = new GatewayPanoramaReport.DynamicRouteReport(
                routeProps.isEnabled(),
                dualTrackEnabled,
                totalRouteCount,
                nacosReport,
                mainReport
        );

        // 2. 金丝雀灰度负载均衡诊断
        AtengGatewayTrafficProperties trafficProps = props.getTraffic() != null
                ? props.getTraffic() : new AtengGatewayTrafficProperties();
        String cacheTtlStr = formatDuration(trafficProps.getCacheTtl(), "3s");
        GatewayPanoramaReport.GrayLoadBalancerReport grayReport = new GatewayPanoramaReport.GrayLoadBalancerReport(
                trafficProps.isEnabled(),
                trafficProps.isFallbackToDefault(),
                cacheTtlStr,
                trafficProps.getZone()
        );

        // 3. 零信任边缘安全诊断 (规则计数，零 IP 泄密)
        AtengGatewaySecurityProperties secProps = props.getSecurity() != null
                ? props.getSecurity() : new AtengGatewaySecurityProperties();
        GatewayPanoramaReport.EdgeSecurityReport secReport = new GatewayPanoramaReport.EdgeSecurityReport(
                secProps.isEnabled(),
                secProps.isSaTokenEnabled(),
                secProps.isPathValidationEnabled(),
                secProps.isHeaderSanitizeEnabled(),
                secProps.isClientIpEnabled(),
                secProps.isIpFilterEnabled(),
                secProps.getIpBlacklist() != null ? secProps.getIpBlacklist().size() : 0,
                secProps.getIpWhitelist() != null ? secProps.getIpWhitelist().size() : 0,
                secProps.getSanitizeHeaders() != null ? secProps.getSanitizeHeaders().size() : 0
        );

        // 4. 开放接口 API 签名防篡改诊断
        AtengGatewaySignProperties signProps = props.getSign() != null
                ? props.getSign() : new AtengGatewaySignProperties();
        String timestampThresholdStr = formatDuration(signProps.getTimestampThreshold(), "5m");
        GatewayPanoramaReport.SignGuardReport signReport = new GatewayPanoramaReport.SignGuardReport(
                signProps.isEnabled(),
                signProps.isBodyEnabled(),
                timestampThresholdStr,
                signProps.getIncludePaths() != null ? signProps.getIncludePaths().size() : 0,
                signProps.getExcludePaths() != null ? signProps.getExcludePaths().size() : 0
        );

        boolean overallHealthy = nacosHealthy && mainHealthy;
        String status = overallHealthy ? "UP" : "DEGRADED";

        return new GatewayPanoramaReport(
                status,
                gatewayId,
                System.currentTimeMillis(),
                dynamicRouteReport,
                grayReport,
                secReport,
                signReport
        );
    }

    private String formatDuration(Duration duration, String defaultVal) {
        if (duration == null) {
            return defaultVal;
        }
        long seconds = duration.toSeconds();
        if (seconds >= 60 && seconds % 60 == 0) {
            return duration.toMinutes() + "m";
        }
        return seconds + "s";
    }
}
