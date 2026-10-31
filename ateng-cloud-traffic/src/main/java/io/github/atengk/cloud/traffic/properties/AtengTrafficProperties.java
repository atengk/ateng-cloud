package io.github.atengk.cloud.traffic.properties;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 全链路流量治理与金丝雀灰度配置属性
 *
 * @author Ateng
 * @since 2026-10-29
 */
@ConfigurationProperties(prefix = TrafficConstants.PROPERTIES_PREFIX)
public class AtengTrafficProperties {

    /**
     * 是否启用全链路流量治理与灰度调度
     */
    private boolean enabled = true;

    /**
     * 流量染色请求头键名
     */
    private String headerName = TrafficConstants.HEADER_TRAFFIC_TAG;

    /**
     * 流量染色 URL Query 参数键名
     */
    private String paramName = TrafficConstants.PARAM_TRAFFIC_TAG;

    /**
     * 目标灰度实例全下线或未命中时，是否平滑降级至基线实例 (杜绝 503 报警)
     */
    private boolean fallbackToDefault = true;

    /**
     * 是否开启金融级严格泳道隔离模式 (未命中灰度实例时直接阻断抛出异常，优先级高于 fallbackToDefault)
     */
    private boolean strictLane = false;

    /**
     * 是否开启跨跳泳道穿透保持语义 (中间服务降级至基线后，后续出站调用依然优先重回灰度泳道)
     */
    private boolean laneContinuity = true;

    /**
     * 入站流量染色过滤器执行顺序 (默认最高优先级后移 50，位于链路追踪 Filter 之后)
     */
    private int filterOrder = Ordered.HIGHEST_PRECEDENCE + 50;

    /**
     * 客户端负载均衡本地服务实例缓存时效 (默认 3 秒，收敛 Spring 默认 35 秒盲区)
     */
    private Duration cacheTtl = Duration.ofSeconds(3);

    /**
     * 允许放行的合法流量染色标签白名单集合 (为空表示不启用白名单校验，全量放行)
     */
    private Set<String> allowedTags = new LinkedHashSet<>();

    /**
     * 当检测到未授权流量染色标签时的安全处置策略 (默认 STRIP: 剥离并降级至基线；REJECT: 阻断抛出 4211 异常)
     */
    private TrafficWhitelistAction whitelistAction = TrafficWhitelistAction.STRIP;

    /**
     * Nacos 动态流量规则分级监听配置
     */
    @NestedConfigurationProperty
    private TrafficNacosProperties nacos = new TrafficNacosProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public String getParamName() {
        return paramName;
    }

    public void setParamName(String paramName) {
        this.paramName = paramName;
    }

    public boolean isFallbackToDefault() {
        return fallbackToDefault;
    }

    public void setFallbackToDefault(boolean fallbackToDefault) {
        this.fallbackToDefault = fallbackToDefault;
    }

    public boolean isStrictLane() {
        return strictLane;
    }

    public void setStrictLane(boolean strictLane) {
        this.strictLane = strictLane;
    }

    public boolean isLaneContinuity() {
        return laneContinuity;
    }

    public void setLaneContinuity(boolean laneContinuity) {
        this.laneContinuity = laneContinuity;
    }

    public int getFilterOrder() {
        return filterOrder;
    }

    public void setFilterOrder(int filterOrder) {
        this.filterOrder = filterOrder;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public Set<String> getAllowedTags() {
        return allowedTags;
    }

    public void setAllowedTags(Set<String> allowedTags) {
        this.allowedTags = allowedTags != null ? new LinkedHashSet<>(allowedTags) : new LinkedHashSet<>();
    }

    public TrafficWhitelistAction getWhitelistAction() {
        return whitelistAction;
    }

    public void setWhitelistAction(TrafficWhitelistAction whitelistAction) {
        this.whitelistAction = whitelistAction != null ? whitelistAction : TrafficWhitelistAction.STRIP;
    }

    public TrafficNacosProperties getNacos() {
        return nacos;
    }

    public void setNacos(TrafficNacosProperties nacos) {
        this.nacos = nacos;
    }
}
