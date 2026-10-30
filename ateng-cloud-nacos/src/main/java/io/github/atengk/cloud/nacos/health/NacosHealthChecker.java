package io.github.atengk.cloud.nacos.health;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.nacos.api.config.ConfigService;
import io.github.atengk.cloud.nacos.constant.NacosConfigConstants;
import io.github.atengk.cloud.nacos.metadata.DefaultNacosMetadataProvider;
import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import io.github.atengk.cloud.nacos.metadata.NacosWarmupHelper;
import io.github.atengk.cloud.nacos.metrics.NacosMetricsManager;
import io.github.atengk.cloud.nacos.network.NacosNetworkAddressDetector;
import io.github.atengk.cloud.nacos.network.NetworkAddressResult;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

/**
 * Nacos 全景健康就绪诊断器与深水区安全防线
 *
 * <p>在所有单例 Bean 实例化完成后统一收敛执行：
 * <ul>
 *     <li>Spring Boot 3 {@code spring.config.import} 规范与激活属性源拓扑诊断</li>
 *     <li>Nacos 远端服务端 gRPC 长连接连通性与服务状态探测</li>
 *     <li>Kubernetes 容器停机排空安全时限（{@code drain-timeout}）对比与防强杀预警</li>
 *     <li>本地容灾快照（Failover Snapshot）磁盘目录创建与文件写入权限探针</li>
 *     <li>启动预热（Startup Preheating）倒计时与同机房可用区亲和拓扑全景汇总</li>
 * </ul>
 * 集中输出规范化就绪诊断报告，防范微服务深水区运行隐患。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class NacosHealthChecker implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(NacosHealthChecker.class);

    /**
     * 容器环境下推荐的优雅停机排空上限阈值（25 秒，严格防范 Kubernetes 默认 30s terminationGracePeriodSeconds 强杀）
     */
    public static final Duration MAX_RECOMMENDED_DRAIN_TIMEOUT = Duration.ofSeconds(25);

    private final AtengNacosProperties properties;
    private final Environment environment;
    private final ObjectProvider<NacosConfigManager> configManagerProvider;
    private final ObjectProvider<NacosMetadataProvider> metadataProvider;
    private final ObjectProvider<NacosTemplate> nacosTemplateProvider;
    private final ObjectProvider<NacosMetricsManager> metricsManagerProvider;
    private final ObjectProvider<NacosNetworkAddressDetector> networkAddressDetectorProvider;
    private final ObjectProvider<NacosDiscoveryProperties> discoveryPropertiesProvider;

    public NacosHealthChecker(AtengNacosProperties properties,
                              Environment environment,
                              ObjectProvider<NacosConfigManager> configManagerProvider) {
        this(properties, environment, configManagerProvider, null, null, null, null, null);
    }

    public NacosHealthChecker(AtengNacosProperties properties,
                              Environment environment,
                              ObjectProvider<NacosConfigManager> configManagerProvider,
                              @Nullable ObjectProvider<NacosMetadataProvider> metadataProvider,
                              @Nullable ObjectProvider<NacosTemplate> nacosTemplateProvider) {
        this(properties, environment, configManagerProvider, metadataProvider, nacosTemplateProvider, null, null, null);
    }

    public NacosHealthChecker(AtengNacosProperties properties,
                              Environment environment,
                              ObjectProvider<NacosConfigManager> configManagerProvider,
                              @Nullable ObjectProvider<NacosMetadataProvider> metadataProvider,
                              @Nullable ObjectProvider<NacosTemplate> nacosTemplateProvider,
                              @Nullable ObjectProvider<NacosMetricsManager> metricsManagerProvider) {
        this(properties, environment, configManagerProvider, metadataProvider, nacosTemplateProvider, metricsManagerProvider, null, null);
    }

    public NacosHealthChecker(AtengNacosProperties properties,
                              Environment environment,
                              ObjectProvider<NacosConfigManager> configManagerProvider,
                              @Nullable ObjectProvider<NacosMetadataProvider> metadataProvider,
                              @Nullable ObjectProvider<NacosTemplate> nacosTemplateProvider,
                              @Nullable ObjectProvider<NacosMetricsManager> metricsManagerProvider,
                              @Nullable ObjectProvider<NacosNetworkAddressDetector> networkAddressDetectorProvider,
                              @Nullable ObjectProvider<NacosDiscoveryProperties> discoveryPropertiesProvider) {
        this.properties = properties;
        this.environment = environment;
        this.configManagerProvider = configManagerProvider;
        this.metadataProvider = metadataProvider;
        this.nacosTemplateProvider = nacosTemplateProvider;
        this.metricsManagerProvider = metricsManagerProvider;
        this.networkAddressDetectorProvider = networkAddressDetectorProvider;
        this.discoveryPropertiesProvider = discoveryPropertiesProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (properties == null || !properties.isEnabled()) {
            return;
        }

        checkAndLogStatus();
    }

    /**
     * 执行全景健康就绪自检并打印规范化诊断报告
     */
    public void checkAndLogStatus() {
        // 1. K8s 停机排空安全时限对比预警（独立于配置中心开关，容器通用安全防卫）
        checkDrainTimeout();

        // 2. 本地容灾快照磁盘可写性探针预检（独立于配置中心开关，容灾通用安全防卫）
        boolean snapshotWritable = checkSnapshotDirectoryWritable();

        // 3. 配置中心与属性源导入自检（受 checkOnStartup 配置项控制）
        boolean configCheckEnabled = properties.getConfig() == null || properties.getConfig().isCheckOnStartup();
        boolean hasImportDirective = configCheckEnabled && detectConfigImportDirective();
        List<String> activeNacosSources = configCheckEnabled ? detectActiveNacosPropertySources() : Collections.emptyList();
        String serverStatus = detectServerStatus();
        String defaultGroup = properties.getConfig() != null
                ? properties.getConfig().getDefaultGroup() : "DEFAULT_GROUP";

        // 4. 网卡绑定与宿主 IP 探测（防范多网卡与回环地址安全风险）
        NetworkAddressResult networkResult = detectNetworkAddress();
        if (networkResult != null && networkResult.isLoopback()) {
            log.warn("[ateng-cloud-nacos] [高危安全告警] 检测到当前绑定的微服务注册 IP 为回环地址 (127.0.0.1)！"
                    + "该节点将无法被其他微服务节点或网关跨主机寻址访问，仅推荐在单机离线自测环境下使用。"
                    + "建议配置 ateng.cloud.nacos.network.preferred-networks 或 network-interface 显式指定内网物理网卡！");
        }

        // 5. 元数据与预热状态采集
        String serviceName = environment != null
                ? environment.getProperty("spring.application.name", "unknown") : "unknown";

        NacosMetadataProvider metaProvider = metadataProvider != null ? metadataProvider.getIfAvailable() : null;
        Map<String, String> sortedMeta = resolveMetadataSnapshot();

        String zone = sortedMeta.get(DefaultNacosMetadataProvider.KEY_ZONE);
        if (!StringUtils.hasText(zone)) {
            zone = properties.getZone();
        }
        String cluster = sortedMeta.get(DefaultNacosMetadataProvider.KEY_CLUSTER);
        if (!StringUtils.hasText(cluster)) {
            cluster = properties.getCluster();
        }

        NacosWarmupHelper.WarmupStatus warmup = NacosWarmupHelper.calculateWarmupStatus(metaProvider, properties);

        // 6. 格式化输出全景就绪自检报告
        StringBuilder sb = new StringBuilder("\n");
        sb.append("=================== [ateng-cloud-nacos 全景健康就绪诊断报告] ===================\n");
        sb.append(String.format(" 应用标识 (Application)    : %s\n", serviceName));
        sb.append(String.format(" 服务端状态 (ServerStatus) : %s\n", serverStatus));
        if (networkResult != null) {
            sb.append(String.format(" 网卡绑定 (NetworkBind)    : Interface=%s, IP=%s%s\n",
                    networkResult.getNetworkInterface(),
                    networkResult.getIp(),
                    networkResult.isLoopback() ? " [WARN: LOOPBACK]" : ""));
        }
        sb.append(String.format(" 可用区机房 (Zone/Cluster) : Zone=%s, Cluster=%s\n",
                StringUtils.hasText(zone) ? zone : "N/A",
                StringUtils.hasText(cluster) ? cluster : "DEFAULT"));
        sb.append(String.format(" 优雅停机 (DrainTimeout)  : %s\n",
                properties.getDrainTimeout() != null ? properties.getDrainTimeout() : "10s"));
        sb.append(String.format(" 容灾快照 (SnapshotDisk)  : %s\n",
                snapshotWritable ? "HEALTHY (Writable)" : "WARN (Non-Writable)"));
        sb.append(String.format(" 启动预热 (WarmupStatus)  : Duration=%s, Remaining=%ds, Completed=%s\n",
                warmup.warmupDuration(), warmup.warmupRemainingSeconds(), warmup.warmupCompleted()));
        sb.append(String.format(" 动态配置 (ConfigSources) : %s (Active: %d, DefaultGroup: %s)\n",
                activeNacosSources.isEmpty() ? (hasImportDirective ? "OFFLINE/STANDBY" : "BYPASS") : "CONNECTED",
                activeNacosSources.size(), defaultGroup));
        if (!activeNacosSources.isEmpty()) {
            sb.append(String.format(" 已激活配置源列表         : %s\n", activeNacosSources));
        }
        sb.append(String.format(" 实例元数据拓扑 (Metadata): %s\n", sortedMeta));
        sb.append("================================================================================");

        log.info(sb.toString());
    }

    /**
     * 探测绑定的物理网卡名称、选中的宿主 IP 及回环状态
     *
     * @return 侦测到的网络地址探测结果对象，保证不为 null
     */
    public NetworkAddressResult detectNetworkAddress() {
        NacosDiscoveryProperties discoveryProperties = discoveryPropertiesProvider != null
                ? discoveryPropertiesProvider.getIfAvailable() : null;
        NacosNetworkAddressDetector detector = networkAddressDetectorProvider != null
                ? networkAddressDetectorProvider.getIfAvailable() : null;

        // 1. 若 discoveryProperties 已配置具体 IP
        if (discoveryProperties != null && StringUtils.hasText(discoveryProperties.getIp())) {
            String ip = discoveryProperties.getIp().trim();
            boolean isLoopback = "127.0.0.1".equals(ip) || "localhost".equalsIgnoreCase(ip);
            String iface = "configured";
            if (detector != null && properties != null) {
                try {
                    NetworkAddressResult detected = detector.detect(properties.getNetwork());
                    if (detected != null && ip.equals(detected.getIp())) {
                        iface = detected.getNetworkInterface();
                    }
                } catch (Exception ignored) {
                }
            }
            return new NetworkAddressResult(ip, iface, isLoopback);
        }

        // 2. 若存在 detector 则主动检测
        if (detector != null) {
            try {
                AtengNacosProperties.NetworkProperties netProps = properties != null ? properties.getNetwork() : null;
                return detector.detect(netProps);
            } catch (Exception ex) {
                log.debug("NacosNetworkAddressDetector execution failed: {}", ex.getMessage());
            }
        }

        // 3. 离线无探测器保底
        return new NetworkAddressResult("127.0.0.1", "lo", true);
    }

    /**
     * 采集并按字典序排列注入注册中心的实例元数据全量快照
     *
     * @return 按键名自然排序的实例元数据字典，不可为 null
     */
    public Map<String, String> resolveMetadataSnapshot() {
        NacosDiscoveryProperties discoveryProps = discoveryPropertiesProvider != null
                ? discoveryPropertiesProvider.getIfAvailable() : null;
        NacosMetadataProvider metaProvider = metadataProvider != null
                ? metadataProvider.getIfAvailable() : null;

        Map<String, String> meta = Collections.emptyMap();
        if (discoveryProps != null && discoveryProps.getMetadata() != null && !discoveryProps.getMetadata().isEmpty()) {
            meta = discoveryProps.getMetadata();
        } else if (metaProvider != null) {
            meta = metaProvider.getMetadata();
        }
        return Collections.unmodifiableMap(new TreeMap<>(meta));
    }

    /**
     * 校验 Kubernetes 优雅停机在途请求排空时限，超标时输出风险警告
     *
     * @return 若在安全阈值（<=25s）内返回 true，超标返回 false
     */
    public boolean checkDrainTimeout() {
        if (properties == null) {
            return true;
        }
        Duration drainTimeout = properties.getDrainTimeout();
        if (drainTimeout != null && drainTimeout.compareTo(MAX_RECOMMENDED_DRAIN_TIMEOUT) > 0) {
            log.warn("[ateng-cloud-nacos] [安全告警] 当前配置的优雅停机排空时限 drain-timeout ({}s) 超过了推荐安全阈值 25s！"
                            + "Kubernetes 默认 terminationGracePeriodSeconds 通常为 30s，排空时限过长可能导致在途请求尚未处理完毕即被容器 Runtime 强制发送 SIGKILL 强杀。"
                            + "建议将 drain-timeout 设置在 10s~15s 之间，且严格小于容器终止等待时限！",
                    drainTimeout.getSeconds());
            return false;
        }
        return true;
    }

    /**
     * 解析 Nacos 客户端本地容灾快照基础存储路径
     *
     * @return 快照配置目录抽象路径
     */
    public Path resolveSnapshotDirectory() {
        String snapshotBasePath = System.getProperty("JM.SNAPSHOT.PATH");
        if (!StringUtils.hasText(snapshotBasePath)) {
            snapshotBasePath = System.getProperty("user.home");
        }
        if (!StringUtils.hasText(snapshotBasePath)) {
            snapshotBasePath = ".";
        }
        return Paths.get(snapshotBasePath, "nacos", "config");
    }

    /**
     * 使用默认本地容灾快照路径执行磁盘可写性探测
     *
     * @return 若目录可写且能成功执行探测文件写入则返回 true，否则返回 false 并记录安全告警
     */
    public boolean checkSnapshotDirectoryWritable() {
        return checkSnapshotDirectoryWritable(resolveSnapshotDirectory());
    }

    /**
     * 探针检测指定容灾快照目录的创建与文件写入权限
     *
     * @param snapshotDir 待探测的快照存储目录
     * @return 若目录可写且能写入探测文件则返回 true，否则返回 false
     */
    public boolean checkSnapshotDirectoryWritable(@Nullable Path snapshotDir) {
        if (snapshotDir == null) {
            return false;
        }
        Path probeFile = null;
        try {
            if (!Files.exists(snapshotDir)) {
                Files.createDirectories(snapshotDir);
            }
            if (!Files.isWritable(snapshotDir)) {
                logSnapshotWarning(snapshotDir, "目录无写权限 (isWritable=false)");
                return false;
            }
            // 尝试创建探测临时文件并确保在 finally 绝对删除，确保底层磁盘真实可写（防御只读文件系统且杜绝文件残留）
            probeFile = snapshotDir.resolve(".probe_" + UUID.randomUUID());
            Files.createFile(probeFile);
            Files.writeString(probeFile, "health-probe");
            return true;
        } catch (Exception ex) {
            logSnapshotWarning(snapshotDir, ex.getMessage());
            return false;
        } finally {
            if (probeFile != null) {
                try {
                    Files.deleteIfExists(probeFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void logSnapshotWarning(Path snapshotDir, String reason) {
        log.warn("[ateng-cloud-nacos] [安全告警] 本地容灾快照 (Failover Snapshot) 目录 '{}' 探测写入失败 (原因: {})！"
                        + "在 Docker/Kubernetes 开启只读根文件系统 (readOnlyRootFilesystem: true) 场景下，"
                        + "若未给该目录挂载可写存储卷 (emptyDir/Volume)，当 Nacos 服务端发生网络分区或停机时，"
                        + "微服务将无法持久化与读取本地容灾快照，导致本地容灾降级静默失效！建议挂载可写存储卷。",
                snapshotDir.toAbsolutePath(), reason);
    }

    /**
     * 探测当前环境是否声明了 nacos 配置导入指令
     *
     * @return 若声明了有效的 nacos 配置导入指令则返回 true
     */
    public boolean detectConfigImportDirective() {
        if (environment == null) {
            return false;
        }

        // 1. 探测单一属性声明 spring.config.import
        String singleImport = environment.getProperty("spring.config.import");
        if (StringUtils.hasText(singleImport) && singleImport.contains(NacosConfigConstants.PREFIX_NACOS_IMPORT)) {
            return true;
        }

        // 2. 依托 Spring Boot 原生类型安全 Binder 探测列表声明（YAML 列表或 indexed 属性），杜绝硬编码循环与数组边界溢出
        try {
            Binder binder = Binder.get(environment);
            BindResult<List<String>> importList = binder.bind("spring.config.import", Bindable.listOf(String.class));
            if (importList.isBound() && importList.get() != null) {
                for (String item : importList.get()) {
                    if (StringUtils.hasText(item) && item.contains(NacosConfigConstants.PREFIX_NACOS_IMPORT)) {
                        return true;
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("Failed to bind spring.config.import via Binder: {}", ex.getMessage());
        }

        return false;
    }

    /**
     * 探测当前 Environment 中已激活的 Nacos 属性源名称集合
     *
     * @return 激活的 Nacos 属性源名称列表，不可为 null
     */
    public List<String> detectActiveNacosPropertySources() {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return Collections.emptyList();
        }

        List<String> nacosSources = new ArrayList<>();
        for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
            if (propertySource == null) {
                continue;
            }
            String name = propertySource.getName();
            if (name != null && (name.contains("nacos:")
                    || name.toLowerCase().contains("nacospropertysource")
                    || name.startsWith("NacosPropertySource"))) {
                nacosSources.add(name);
            }
        }
        return Collections.unmodifiableList(nacosSources);
    }

    /**
     * 探测 Nacos 服务端连接与就绪状态（覆盖配置中心与服务注册发现双模状态）
     *
     * @return 服务端状态字符串（如 UP、DISCONNECTED、UNAVAILABLE 等）
     */
    public String detectServerStatus() {
        // 1. 若注入了 MetricsManager 且当前 gRPC 连接处于断开态，直接 Fail-Fast 判定为 DISCONNECTED
        if (metricsManagerProvider != null) {
            NacosMetricsManager metricsManager = metricsManagerProvider.getIfAvailable();
            if (metricsManager != null && metricsManager.getGrpcConnectionStatus() == 0.0) {
                return "DISCONNECTED";
            }
        }

        // 2. 优先通过 NacosTemplate 探查配置与服务发现综合健康态
        try {
            NacosTemplate template = nacosTemplateProvider != null ? nacosTemplateProvider.getIfAvailable() : null;
            if (template != null) {
                if (template.isConfigAvailable()) {
                    String status = template.getConfigServerStatus();
                    if (StringUtils.hasText(status) && !"UNKNOWN".equalsIgnoreCase(status)) {
                        return status;
                    }
                }
                if (template.isDiscoveryAvailable()) {
                    String namingStatus = template.getNamingServerStatus();
                    if (StringUtils.hasText(namingStatus) && !"UNKNOWN".equalsIgnoreCase(namingStatus)) {
                        return namingStatus;
                    }
                    return "UP";
                }
            }
        } catch (Exception ex) {
            log.debug("Failed to detect Nacos server status via NacosTemplate: {}", ex.getMessage());
        }

        // 3. 尝试从 NacosConfigManager 探查配置中心连接状态
        NacosConfigManager configManager = configManagerProvider != null
                ? configManagerProvider.getIfAvailable() : null;
        if (configManager == null) {
            return "UNAVAILABLE";
        }
        try {
            ConfigService configService = configManager.getConfigService();
            if (configService != null) {
                String status = configService.getServerStatus();
                return StringUtils.hasText(status) ? status : "UNKNOWN";
            }
        } catch (Exception ex) {
            log.debug("Failed to detect Nacos server status: {}", ex.getMessage());
        }
        return "DISCONNECTED";
    }
}
