package io.github.atengk.cloud.traffic.properties;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
     * 按下游目标服务名精细化开启严格泳道隔离的 serviceId 集合 (忽略大小写匹配)
     */
    private Set<String> strictLaneServices = new LinkedHashSet<>();

    /**
     * 按流量染色标签精细化开启严格泳道隔离的 tag 集合 (忽略大小写匹配)
     */
    private Set<String> strictLaneTags = new LinkedHashSet<>();

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
     * 当检测到未授权流量染色标签时的安全处置策略 (默认 STRIP: 剥离并降级至基线；REJECT: 阻断抛出 4212 异常)
     */
    private TrafficWhitelistAction whitelistAction = TrafficWhitelistAction.STRIP;

    /**
     * Nacos 动态流量规则分级监听配置
     */
    @NestedConfigurationProperty
    private TrafficNacosProperties nacos = new TrafficNacosProperties();

    /**
     * 本地静态配置的流量分流规则列表 (Hierarchical Rule Resolution 第三级本地兜底源)
     */
    @NestedConfigurationProperty
    private List<TrafficRule> rules = new ArrayList<>();

    /**
     * Actuator 诊断与沙箱端点配置
     */
    @NestedConfigurationProperty
    private TrafficActuatorProperties actuator = new TrafficActuatorProperties();

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

    /**
     * 解析当前生效的流量染色 HTTP 请求头键名（缺省回退至标准常量 {@code X-Traffic-Tag}）
     *
     * @return 规范化后的请求头键名
     */
    public String resolveHeaderName() {
        return headerName != null && !headerName.isBlank()
                ? headerName.trim()
                : TrafficConstants.HEADER_TRAFFIC_TAG;
    }

    public String getParamName() {
        return paramName;
    }

    public void setParamName(String paramName) {
        this.paramName = paramName;
    }

    /**
     * 解析当前生效的流量染色 URL Query 参数键名（缺省回退至标准常量 {@code traffic_tag}）
     *
     * @return 规范化后的查询参数键名
     */
    public String resolveParamName() {
        return paramName != null && !paramName.isBlank()
                ? paramName.trim()
                : TrafficConstants.PARAM_TRAFFIC_TAG;
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

    public Set<String> getStrictLaneServices() {
        return strictLaneServices;
    }

    public void setStrictLaneServices(Set<String> strictLaneServices) {
        this.strictLaneServices = strictLaneServices != null
                ? new LinkedHashSet<>(strictLaneServices)
                : new LinkedHashSet<>();
    }

    public Set<String> getStrictLaneTags() {
        return strictLaneTags;
    }

    public void setStrictLaneTags(Set<String> strictLaneTags) {
        this.strictLaneTags = strictLaneTags != null
                ? new LinkedHashSet<>(strictLaneTags)
                : new LinkedHashSet<>();
    }

    /**
     * 综合判定当前调用是否需要激活严格泳道隔离 (全局 strict-lane、按目标服务名或按流量标签任一命中即生效，忽略大小写)
     *
     * @param serviceId 下游目标服务名 (可为 null)
     * @param tag       当前活跃流量染色标签 (可为 null)
     * @return true 表示命中强隔离，缺失灰度实例时必须 Fail-Fast 阻断；false 表示允许平滑降级
     */
    public boolean isStrictLaneRequired(String serviceId, String tag) {
        if (strictLane) {
            return true;
        }
        if (containsIgnoreCase(strictLaneServices, serviceId)) {
            return true;
        }
        return containsIgnoreCase(strictLaneTags, tag);
    }

    private static boolean containsIgnoreCase(Set<String> candidates, String value) {
        if (candidates == null || candidates.isEmpty() || value == null || value.isBlank()) {
            return false;
        }
        String target = value.trim();
        for (String candidate : candidates) {
            if (candidate != null && candidate.trim().equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
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

    public List<TrafficRule> getRules() {
        return rules;
    }

    public void setRules(List<TrafficRule> rules) {
        this.rules = rules != null ? new ArrayList<>(rules) : new ArrayList<>();
    }

    /**
     * 解析本地静态分流规则列表，统一过滤空元素并返回不可变只读视图
     *
     * @return 不可变本地静态规则列表 (永不为 null)
     */
    public List<TrafficRule> resolveLocalRules() {
        if (rules == null || rules.isEmpty()) {
            return Collections.emptyList();
        }
        List<TrafficRule> sanitized = new ArrayList<>(rules.size());
        for (TrafficRule rule : rules) {
            if (rule != null) {
                sanitized.add(rule);
            }
        }
        return sanitized.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(sanitized);
    }

    public TrafficActuatorProperties getActuator() {
        return actuator;
    }

    public void setActuator(TrafficActuatorProperties actuator) {
        this.actuator = actuator != null ? actuator : new TrafficActuatorProperties();
    }
}
