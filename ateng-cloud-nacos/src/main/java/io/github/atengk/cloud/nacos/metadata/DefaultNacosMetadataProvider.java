package io.github.atengk.cloud.nacos.metadata;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.spi.NacosMetadataCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.OrderComparator;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 Nacos 实例元数据提供者实现
 *
 * <p>负责自动采集版本号、运行环境、JVM 启动时间戳、60s 预热时长、可用区机房与业务自定义标签，
 * 并依序交由 {@link NacosMetadataCustomizer} 进行 SPI 扩展。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class DefaultNacosMetadataProvider implements NacosMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultNacosMetadataProvider.class);

    public static final String KEY_STARTUP_TIME = "startup-time";
    public static final String KEY_WARMUP_DURATION = "warmup-duration";
    public static final String KEY_VERSION = "version";
    public static final String KEY_ENV = "env";
    public static final String KEY_ZONE = "zone";
    public static final String KEY_CLUSTER = "cluster";
    public static final String KEY_TRAFFIC_TAG = "traffic-tag";

    private final Map<String, String> cachedMetadata = new ConcurrentHashMap<>();

    public DefaultNacosMetadataProvider(AtengNacosProperties properties,
                                        Environment environment,
                                        List<NacosMetadataCustomizer> customizers) {
        // 1. 初始化收集基础规范元数据
        Map<String, String> initialMap = new LinkedHashMap<>();

        // 1.1 启动时间戳与平滑预热窗口
        AtengNacosProperties.MetadataProperties metaProps = properties.getMetadata();
        if (metaProps.isStartupTime()) {
            initialMap.put(KEY_STARTUP_TIME, String.valueOf(System.currentTimeMillis()));
        }
        if (metaProps.getWarmupDuration() != null) {
            long seconds = metaProps.getWarmupDuration().getSeconds();
            initialMap.put(KEY_WARMUP_DURATION, seconds > 0 ? seconds + "s" : "60s");
        }

        // 1.2 版本号采集
        String version = metaProps.getVersion();
        if (!StringUtils.hasText(version)) {
            version = environment.getProperty("project.version");
            if (!StringUtils.hasText(version)) {
                version = environment.getProperty("spring.application.version", "1.0.0");
            }
        }
        if (StringUtils.hasText(version)) {
            initialMap.put(KEY_VERSION, version);
        }

        // 1.3 运行环境 Profile
        String env = metaProps.getEnv();
        if (!StringUtils.hasText(env)) {
            String[] activeProfiles = environment.getActiveProfiles();
            if (activeProfiles.length > 0) {
                env = activeProfiles[0];
            } else {
                String[] defaultProfiles = environment.getDefaultProfiles();
                env = (defaultProfiles.length > 0) ? defaultProfiles[0] : "default";
            }
        }
        if (StringUtils.hasText(env)) {
            initialMap.put(KEY_ENV, env);
        }

        // 1.4 机房可用区 Zone
        String zone = metaProps.getZone();
        if (!StringUtils.hasText(zone)) {
            zone = properties.getZone();
        }
        if (!StringUtils.hasText(zone)) {
            zone = environment.getProperty("ZONE", environment.getProperty("REGION", ""));
        }
        if (StringUtils.hasText(zone)) {
            initialMap.put(KEY_ZONE, zone);
        }

        // 1.5 集群标识
        String cluster = metaProps.getCluster();
        if (!StringUtils.hasText(cluster)) {
            cluster = properties.getCluster();
        }
        if (!StringUtils.hasText(cluster)) {
            cluster = environment.getProperty("spring.cloud.nacos.discovery.cluster-name", "");
        }
        if (StringUtils.hasText(cluster)) {
            initialMap.put(KEY_CLUSTER, cluster);
        }

        // 1.6 流量染色标记
        String trafficTag = metaProps.getTrafficTag();
        if (!StringUtils.hasText(trafficTag)) {
            trafficTag = environment.getProperty("X_TRAFFIC_TAG", environment.getProperty("ateng.cloud.traffic.tag", ""));
        }
        if (StringUtils.hasText(trafficTag)) {
            initialMap.put(KEY_TRAFFIC_TAG, trafficTag);
        }

        // 1.7 业务自定义字典
        if (metaProps.getCustom() != null && !metaProps.getCustom().isEmpty()) {
            initialMap.putAll(metaProps.getCustom());
        }

        // 2. 调度执行 SPI 定制链
        if (customizers != null && !customizers.isEmpty()) {
            List<NacosMetadataCustomizer> sortedCustomizers = new ArrayList<>(customizers);
            sortedCustomizers.sort(OrderComparator.INSTANCE);
            for (NacosMetadataCustomizer customizer : sortedCustomizers) {
                try {
                    customizer.customize(initialMap);
                } catch (Exception ex) {
                    log.error("Failed to execute NacosMetadataCustomizer: {}", customizer.getClass().getName(), ex);
                }
            }
        }

        this.cachedMetadata.putAll(initialMap);
        log.info("ateng-cloud Nacos instance metadata initialized: {}", this.cachedMetadata);
    }

    @Override
    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(cachedMetadata));
    }

    @Override
    public void populateMetadata(Map<String, String> targetMetadata) {
        if (targetMetadata != null) {
            targetMetadata.putAll(cachedMetadata);
        }
    }
}
