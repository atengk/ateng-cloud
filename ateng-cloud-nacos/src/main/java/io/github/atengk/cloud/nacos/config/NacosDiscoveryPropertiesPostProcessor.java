package io.github.atengk.cloud.nacos.config;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import io.github.atengk.cloud.nacos.network.NacosNetworkAddressDetector;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Nacos 服务发现属性后置处理器
 *
 * <p>在 {@link NacosDiscoveryProperties} 初始化前，自动注入智能检测的注册网卡 IP、
 * 集群标识与多维基础元数据。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class NacosDiscoveryPropertiesPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(NacosDiscoveryPropertiesPostProcessor.class);

    private final ObjectProvider<AtengNacosProperties> propertiesProvider;
    private final ObjectProvider<NacosNetworkAddressDetector> networkAddressDetectorProvider;
    private final ObjectProvider<NacosMetadataProvider> metadataProvider;
    private final ObjectProvider<Environment> environmentProvider;

    public NacosDiscoveryPropertiesPostProcessor(ObjectProvider<AtengNacosProperties> propertiesProvider,
                                                ObjectProvider<NacosNetworkAddressDetector> networkAddressDetectorProvider,
                                                ObjectProvider<NacosMetadataProvider> metadataProvider,
                                                ObjectProvider<Environment> environmentProvider) {
        this.propertiesProvider = propertiesProvider;
        this.networkAddressDetectorProvider = networkAddressDetectorProvider;
        this.metadataProvider = metadataProvider;
        this.environmentProvider = environmentProvider;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof NacosDiscoveryProperties discoveryProperties) {
            // 若显式关闭了 Nacos 注册发现 (如 spring.cloud.nacos.discovery.enabled: false)，则平滑 No-Op
            Environment environment = environmentProvider.getIfAvailable();
            boolean discoveryEnabled = environment == null || environment.getProperty("spring.cloud.nacos.discovery.enabled", Boolean.class, true);
            if (!discoveryEnabled) {
                log.debug("Nacos discovery is disabled. Skipping NacosDiscoveryProperties post processing.");
                return bean;
            }

            AtengNacosProperties properties = propertiesProvider.getIfAvailable();
            NacosNetworkAddressDetector networkAddressDetector = networkAddressDetectorProvider.getIfAvailable();
            NacosMetadataProvider metadata = metadataProvider.getIfAvailable();

            // 1. 智能注入精准注册 IP（若未在 YAML 中手动配置 spring.cloud.nacos.discovery.ip）
            if (!StringUtils.hasText(discoveryProperties.getIp()) && networkAddressDetector != null) {
                try {
                    AtengNacosProperties.NetworkProperties netProps = properties != null ? properties.getNetwork() : null;
                    String detectedIp = networkAddressDetector.detectIp(netProps);
                    discoveryProperties.setIp(detectedIp);
                    log.info("Injected detected network IP into NacosDiscoveryProperties: {}", detectedIp);
                } catch (Exception ex) {
                    throw new IllegalStateException("Failed to detect IP for NacosDiscoveryProperties: " + ex.getMessage(), ex);
                }
            }

            // 2. 注入集群标识
            if (!StringUtils.hasText(discoveryProperties.getClusterName()) && properties != null && StringUtils.hasText(properties.getCluster())) {
                discoveryProperties.setClusterName(properties.getCluster());
            }

            // 3. 预先注入基础元数据
            if (metadata != null) {
                if (discoveryProperties.getMetadata() == null) {
                    discoveryProperties.setMetadata(new java.util.LinkedHashMap<>());
                }
                metadata.populateMetadata(discoveryProperties.getMetadata());
            }
        }
        return bean;
    }
}
