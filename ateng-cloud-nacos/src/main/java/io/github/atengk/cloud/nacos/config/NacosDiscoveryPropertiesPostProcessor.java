package io.github.atengk.cloud.nacos.config;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import io.github.atengk.cloud.nacos.network.NacosNetworkAddressDetector;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
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

    private final AtengNacosProperties properties;
    private final NacosNetworkAddressDetector networkAddressDetector;
    private final NacosMetadataProvider metadataProvider;

    public NacosDiscoveryPropertiesPostProcessor(AtengNacosProperties properties,
                                                NacosNetworkAddressDetector networkAddressDetector,
                                                NacosMetadataProvider metadataProvider) {
        this.properties = properties;
        this.networkAddressDetector = networkAddressDetector;
        this.metadataProvider = metadataProvider;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof NacosDiscoveryProperties discoveryProperties) {
            // 1. 智能注入精准注册 IP（若未在 YAML 中手动配置 spring.cloud.nacos.discovery.ip）
            if (!StringUtils.hasText(discoveryProperties.getIp())) {
                try {
                    String detectedIp = networkAddressDetector.detectIp(properties.getNetwork());
                    discoveryProperties.setIp(detectedIp);
                    log.info("Injected detected network IP into NacosDiscoveryProperties: {}", detectedIp);
                } catch (Exception ex) {
                    log.error("Failed to detect IP for NacosDiscoveryProperties: {}", ex.getMessage(), ex);
                    throw ex;
                }
            }

            // 2. 注入集群标识
            if (!StringUtils.hasText(discoveryProperties.getClusterName()) && StringUtils.hasText(properties.getCluster())) {
                discoveryProperties.setClusterName(properties.getCluster());
            }

            // 3. 预先注入基础元数据
            if (metadataProvider != null) {
                if (discoveryProperties.getMetadata() == null) {
                    discoveryProperties.setMetadata(new java.util.LinkedHashMap<>());
                }
                metadataProvider.populateMetadata(discoveryProperties.getMetadata());
            }
        }
        return bean;
    }
}
