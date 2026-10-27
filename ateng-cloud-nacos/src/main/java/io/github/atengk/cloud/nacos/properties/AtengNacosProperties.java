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
}
