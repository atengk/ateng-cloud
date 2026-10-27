package io.github.atengk.cloud.nacos.spi;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.springframework.core.Ordered;

import java.util.Map;

/**
 * 默认实例元数据定制器 SPI 实现
 *
 * <p>提供框架基础架构标签与扩展能力的保底默认实现，自动合并配置项中的自定义字典。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class DefaultNacosMetadataCustomizer implements NacosMetadataCustomizer {

    private final AtengNacosProperties properties;

    public DefaultNacosMetadataCustomizer(AtengNacosProperties properties) {
        this.properties = properties;
    }

    @Override
    public void customize(Map<String, String> metadata) {
        metadata.putIfAbsent("framework", "ateng-cloud");
        if (properties != null && properties.getMetadata() != null && properties.getMetadata().getCustom() != null) {
            properties.getMetadata().getCustom().forEach(metadata::putIfAbsent);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
