package io.github.atengk.cloud.sample.nacos.customizer;

import io.github.atengk.cloud.nacos.spi.NacosMetadataCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 演示通过 SPI 定制业务自定义实例元数据
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Component
public class SampleMetadataCustomizer implements NacosMetadataCustomizer {

    @Override
    public void customize(Map<String, String> metadata) {
        metadata.put("demo-module", "sample-cloud-nacos");
        metadata.put("maintainer", "Ateng");
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
