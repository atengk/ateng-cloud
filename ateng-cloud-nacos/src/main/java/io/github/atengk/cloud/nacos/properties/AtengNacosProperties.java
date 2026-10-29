package io.github.atengk.cloud.nacos.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.*;

/**
 * Nacos 微服务治理核心配置属性
 *
 * <p>统一遵循强类型时间（{@link Duration}）规范，支持元数据注入、网卡智能过滤与生命周期排空配置。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
@ConfigurationProperties(prefix = "ateng.cloud.nacos")
public class AtengNacosProperties {

    /**
     * 是否启用 ateng-cloud Nacos 治理增强
     */
    private boolean enabled = true;

    /**
     * 实例优雅下线在途请求排空安全窗口时限（生产建议 10~15 秒，上限不得超过容器 terminationGracePeriodSeconds）
     */
    private Duration drainTimeout = Duration.ofSeconds(10);

    /**
     * 当前服务部署的默认物理机房 / 可用区标识（如 beijing-az1）
     */
    private String zone = "";

    /**
     * 当前服务所属集群名称
     */
    private String cluster = "";

    /**
     * 多维实例元数据增强配置
     */
    @NestedConfigurationProperty
    private MetadataProperties metadata = new MetadataProperties();

    /**
     * 网络接口与注册 IP 智能识别配置
     */
    @NestedConfigurationProperty
    private NetworkProperties network = new NetworkProperties();

    /**
     * Actuator 专属运维端点配置
     */
    @NestedConfigurationProperty
    private EndpointProperties endpoint = new EndpointProperties();

    /**
     * 动态配置中心治理增强配置
     */
    @NestedConfigurationProperty
    private ConfigProperties config = new ConfigProperties();

    /**
     * Micrometer 监控指标导出配置
     */
    @NestedConfigurationProperty
    private MetricsProperties metrics = new MetricsProperties();

    /**
     * Spring Cloud LoadBalancer 负载均衡治理增强配置
     */
    @NestedConfigurationProperty
    private LoadBalancerProperties loadbalancer = new LoadBalancerProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getDrainTimeout() {
        return drainTimeout;
    }

    public void setDrainTimeout(Duration drainTimeout) {
        this.drainTimeout = drainTimeout;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        this.cluster = cluster;
    }

    public MetadataProperties getMetadata() {
        return metadata;
    }

    public void setMetadata(MetadataProperties metadata) {
        this.metadata = metadata;
    }

    public NetworkProperties getNetwork() {
        return network;
    }

    public void setNetwork(NetworkProperties network) {
        this.network = network;
    }

    public EndpointProperties getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(EndpointProperties endpoint) {
        this.endpoint = endpoint;
    }

    public ConfigProperties getConfig() {
        return config;
    }

    public void setConfig(ConfigProperties config) {
        this.config = config;
    }

    public MetricsProperties getMetrics() {
        return metrics;
    }

    public void setMetrics(MetricsProperties metrics) {
        this.metrics = metrics;
    }

    public LoadBalancerProperties getLoadbalancer() {
        return loadbalancer;
    }

    public void setLoadbalancer(LoadBalancerProperties loadbalancer) {
        this.loadbalancer = loadbalancer;
    }

    /**
     * 判断是否开启了负载均衡启动平滑预热
     */
    public boolean isLoadBalancerWarmupEnabled() {
        return loadbalancer != null && loadbalancer.isWarmup();
    }

    /**
     * 判断是否开启了自适应系统资源动态加权
     */
    public boolean isAdaptiveWeightEnabled() {
        return loadbalancer != null && loadbalancer.isAdaptiveWeightEnabled();
    }

    /**
     * 获取负载均衡启动平滑预热保底底噪权重比例（默认 0.10 即 10%）
     */
    public double getWarmupFloorWeightRatio() {
        return (loadbalancer != null) ? loadbalancer.getWarmupFloorWeightRatio() : 0.10;
    }

    /**
     * 判断是否在客户端子上下文中激活默认的服务实例提供者
     */
    public boolean isLoadBalancerClientSupplierEnabled() {
        return loadbalancer == null || loadbalancer.isClientSupplierEnabled();
    }

    /**
     * 实例元数据增强属性
     */
    public static class MetadataProperties {

        /**
         * 是否自动注入启动时间戳（startup-time），供负载均衡器识别平滑预热爬升
         */
        private boolean startupTime = true;

        /**
         * 平滑启动预热窗口时长（默认 60 秒）
         */
        private Duration warmupDuration = Duration.ofSeconds(60);

        /**
         * 应用版本号（为空时尝试从 Environment 中自动探测）
         */
        private String version = "";

        /**
         * 运行时环境标识（dev/test/uat/prod，为空时自动对齐 Spring Profiles）
         */
        private String env = "";

        /**
         * 机房 / 可用区标识（为空时对齐外层 zone）
         */
        private String zone = "";

        /**
         * 集群标识（为空时对齐外层 cluster）
         */
        private String cluster = "";

        /**
         * 全链路金丝雀/灰度流量染色标记（如 gray）
         */
        private String trafficTag = "";

        /**
         * 业务自定义元数据扩展字典
         */
        private Map<String, String> custom = new LinkedHashMap<>();

        public boolean isStartupTime() {
            return startupTime;
        }

        public void setStartupTime(boolean startupTime) {
            this.startupTime = startupTime;
        }

        public Duration getWarmupDuration() {
            return warmupDuration;
        }

        public void setWarmupDuration(Duration warmupDuration) {
            this.warmupDuration = warmupDuration;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getEnv() {
            return env;
        }

        public void setEnv(String env) {
            this.env = env;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public String getCluster() {
            return cluster;
        }

        public void setCluster(String cluster) {
            this.cluster = cluster;
        }

        public String getTrafficTag() {
            return trafficTag;
        }

        public void setTrafficTag(String trafficTag) {
            this.trafficTag = trafficTag;
        }

        public Map<String, String> getCustom() {
            return custom;
        }

        public void setCustom(Map<String, String> custom) {
            this.custom = custom;
        }
    }

    /**
     * 网络接口与 IP 筛选属性
     */
    public static class NetworkProperties {

        /**
         * 显式指定注册绑定的物理网卡名（如 eth0，非空时强制使用该网卡）
         */
        private String networkInterface = "";

        /**
         * 首选网络网段前缀列表（按优先级匹配，如 10.、172.16.、192.168.）
         */
        private List<String> preferredNetworks = new ArrayList<>();

        /**
         * 忽略的虚拟或桥接网卡名称正则表达式黑名单
         */
        private List<String> ignoredInterfaces = new ArrayList<>(Arrays.asList(
                "docker0", "flannel.*", "cni.*", "veth.*", "vmnet.*", "virbr.*", "br-.*",
                ".*vbox.*", ".*virtualbox.*", ".*vmware.*", ".*hyper-v.*", ".*vethernet.*"
        ));

        /**
         * 当最终仅侦测到 127.0.0.1 回环地址时是否抛出异常阻断启动（生产默认开启 Fail-Fast 防线，杜绝回环 IP 注册）
         */
        private boolean failOnLoopback = true;

        public String getNetworkInterface() {
            return networkInterface;
        }

        public void setNetworkInterface(String networkInterface) {
            this.networkInterface = networkInterface;
        }

        public List<String> getPreferredNetworks() {
            return preferredNetworks;
        }

        public void setPreferredNetworks(List<String> preferredNetworks) {
            this.preferredNetworks = preferredNetworks;
        }

        public List<String> getIgnoredInterfaces() {
            return ignoredInterfaces;
        }

        public void setIgnoredInterfaces(List<String> ignoredInterfaces) {
            this.ignoredInterfaces = ignoredInterfaces;
        }

        public boolean isFailOnLoopback() {
            return failOnLoopback;
        }

        public void setFailOnLoopback(boolean failOnLoopback) {
            this.failOnLoopback = failOnLoopback;
        }
    }

    /**
     * Actuator 运维端点配置
     */
    public static class EndpointProperties {

        /**
         * 是否暴露 /actuator/nacosService 专属运维端点
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Nacos 动态配置中心治理配置
     */
    public static class ConfigProperties {

        /**
         * 是否启用 Nacos 配置中心治理增强
         */
        private boolean enabled = true;

        /**
         * 客户端配置运维操作默认超时时限（强类型 Duration 规范）
         */
        private Duration timeout = Duration.ofSeconds(3);

        /**
         * 默认配置分组名
         */
        private String defaultGroup = "DEFAULT_GROUP";

        /**
         * 启动阶段是否执行配置中心连接与导入自检诊断
         */
        private boolean checkOnStartup = true;

        /**
         * 是否强制开启业务单例不可变性守卫（严禁在 @Service/@Repository 上挂载 @RefreshScope）
         */
        private boolean enforceBeanImmutability = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public String getDefaultGroup() {
            return defaultGroup;
        }

        public void setDefaultGroup(String defaultGroup) {
            this.defaultGroup = defaultGroup;
        }

        public boolean isCheckOnStartup() {
            return checkOnStartup;
        }

        public void setCheckOnStartup(boolean checkOnStartup) {
            this.checkOnStartup = checkOnStartup;
        }

        public boolean isEnforceBeanImmutability() {
            return enforceBeanImmutability;
        }

        public void setEnforceBeanImmutability(boolean enforceBeanImmutability) {
            this.enforceBeanImmutability = enforceBeanImmutability;
        }
    }

    /**
     * Nacos 监控指标配置
     */
    public static class MetricsProperties {

        /**
         * 是否启用 Nacos 治理与热更 Micrometer 监控指标导出
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Spring Cloud LoadBalancer 负载均衡治理增强配置
     */
    public static class LoadBalancerProperties {

        /**
         * 是否激活 Nacos 客户端负载均衡增强（同机房就近亲和与启动预热加权调度）
         */
        private boolean enabled = true;

        /**
         * 是否开启同机房可用区（Zone Affinity）优先调度（本区全宕平滑降级全集群）
         */
        private boolean zoneAffinity = true;

        /**
         * 是否开启平滑启动预热（Startup Preheating）动态权重调度
         */
        private boolean warmup = true;

        /**
         * 启动预热期间保底底噪权重比例（默认 0.10 即 10%，防止冷启动完全饥饿或除零）
         */
        private double warmupFloorWeightRatio = 0.10;

        /**
         * 是否在客户端子上下文中激活默认的服务实例提供者（默认 true；在网关等复合路由场景下可设为 false 让位上层责任链）
         */
        private boolean clientSupplierEnabled = true;

        /**
         * 节点自适应资源加权调度配置
         */
        @NestedConfigurationProperty
        private AdaptiveWeightProperties adaptiveWeight = new AdaptiveWeightProperties();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isZoneAffinity() {
            return zoneAffinity;
        }

        public void setZoneAffinity(boolean zoneAffinity) {
            this.zoneAffinity = zoneAffinity;
        }

        public boolean isWarmup() {
            return warmup;
        }

        public void setWarmup(boolean warmup) {
            this.warmup = warmup;
        }

        public double getWarmupFloorWeightRatio() {
            return warmupFloorWeightRatio;
        }

        public void setWarmupFloorWeightRatio(double warmupFloorWeightRatio) {
            this.warmupFloorWeightRatio = warmupFloorWeightRatio;
        }

        public boolean isClientSupplierEnabled() {
            return clientSupplierEnabled;
        }

        public void setClientSupplierEnabled(boolean clientSupplierEnabled) {
            this.clientSupplierEnabled = clientSupplierEnabled;
        }

        public AdaptiveWeightProperties getAdaptiveWeight() {
            return adaptiveWeight;
        }

        public void setAdaptiveWeight(AdaptiveWeightProperties adaptiveWeight) {
            this.adaptiveWeight = adaptiveWeight;
        }

        /**
         * 判断是否激活了节点自适应系统资源动态加权调度
         */
        public boolean isAdaptiveWeightEnabled() {
            return adaptiveWeight != null && adaptiveWeight.isEnabled();
        }
    }

    /**
     * 节点自适应系统资源动态加权配置
     */
    public static class AdaptiveWeightProperties {

        /**
         * 是否激活基于节点系统资源指标（CPU/内存负载）的自适应动态加权调度
         */
        private boolean enabled = false;

        /**
         * 过载保底底噪权重比例（默认 0.10 即 10%，杜绝节点权重直接归零导致调度饥饿）
         */
        private double minFloorWeightRatio = 0.10;

        /**
         * 中等负载阈值（0.0 ~ 1.0，默认 0.70 即 70%，超过该阈值开始平滑轻度降权）
         */
        private double mediumLoadThreshold = 0.70;

        /**
         * 高负载过载阈值（0.0 ~ 1.0，默认 0.85 即 85%，超过该阈值开始重度降权）
         */
        private double highLoadThreshold = 0.85;

        /**
         * 权重调谐阶梯阻尼防抖比例（默认 0.15 即 15%，波动未超该阈值时保持平稳，避免频繁震荡）
         */
        private double dampingRatio = 0.15;

        /**
         * 动态权重调谐采样评估周期（默认 5s，收敛高频计算开销）
         */
        private Duration sampleInterval = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getMinFloorWeightRatio() {
            return minFloorWeightRatio;
        }

        public void setMinFloorWeightRatio(double minFloorWeightRatio) {
            this.minFloorWeightRatio = minFloorWeightRatio;
        }

        public double getMediumLoadThreshold() {
            return mediumLoadThreshold;
        }

        public void setMediumLoadThreshold(double mediumLoadThreshold) {
            this.mediumLoadThreshold = mediumLoadThreshold;
        }

        public double getHighLoadThreshold() {
            return highLoadThreshold;
        }

        public void setHighLoadThreshold(double highLoadThreshold) {
            this.highLoadThreshold = highLoadThreshold;
        }

        public double getDampingRatio() {
            return dampingRatio;
        }

        public void setDampingRatio(double dampingRatio) {
            this.dampingRatio = dampingRatio;
        }

        public Duration getSampleInterval() {
            return sampleInterval;
        }

        public void setSampleInterval(Duration sampleInterval) {
            this.sampleInterval = sampleInterval;
        }
    }
}
