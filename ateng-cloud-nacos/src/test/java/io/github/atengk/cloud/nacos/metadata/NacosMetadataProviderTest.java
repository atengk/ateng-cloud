package io.github.atengk.cloud.nacos.metadata;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.spi.NacosMetadataCustomizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实例元数据提供者与 SPI 定制单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class NacosMetadataProviderTest {

    @Test
    @DisplayName("测试基础 Kebab-case 元数据自动采集注入")
    void testStandardMetadataCollection() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getMetadata().setStartupTime(true);
        properties.getMetadata().setWarmupDuration(Duration.ofSeconds(60));
        properties.getMetadata().setVersion("1.0.0");
        properties.getMetadata().setEnv("test");
        properties.getMetadata().setZone("beijing-az1");
        properties.getMetadata().setCluster("cluster-a");
        properties.getMetadata().setTrafficTag("gray");
        properties.getMetadata().getCustom().put("team", "infra");

        MockEnvironment env = new MockEnvironment();

        DefaultNacosMetadataProvider provider = new DefaultNacosMetadataProvider(properties, env, Collections.emptyList());
        Map<String, String> metadata = provider.getMetadata();

        assertThat(metadata).containsEntry("version", "1.0.0");
        assertThat(metadata).containsEntry("env", "test");
        assertThat(metadata).containsEntry("zone", "beijing-az1");
        assertThat(metadata).containsEntry("cluster", "cluster-a");
        assertThat(metadata).containsEntry("traffic-tag", "gray");
        assertThat(metadata).containsEntry("warmup-duration", "60s");
        assertThat(metadata).containsEntry("team", "infra");
        assertThat(metadata).containsKey("startup-time");
        assertThat(Long.parseLong(metadata.get("startup-time"))).isGreaterThan(0);
    }

    @Test
    @DisplayName("测试 SPI Customizer 依序定制与覆写元数据")
    void testSpiCustomizers() {
        AtengNacosProperties properties = new AtengNacosProperties();
        MockEnvironment env = new MockEnvironment();

        NacosMetadataCustomizer customizer1 = new NacosMetadataCustomizer() {
            @Override
            public void customize(Map<String, String> metadata) {
                metadata.put("customizer-1", "val1");
            }

            @Override
            public int getOrder() {
                return 10;
            }
        };

        NacosMetadataCustomizer customizer2 = new NacosMetadataCustomizer() {
            @Override
            public void customize(Map<String, String> metadata) {
                metadata.put("customizer-2", "val2");
            }

            @Override
            public int getOrder() {
                return 20;
            }
        };

        DefaultNacosMetadataProvider provider = new DefaultNacosMetadataProvider(
                properties, env, Arrays.asList(customizer2, customizer1)
        );

        Map<String, String> metadata = provider.getMetadata();
        assertThat(metadata).containsEntry("customizer-1", "val1");
        assertThat(metadata).containsEntry("customizer-2", "val2");

        // 测试填充至目标 Map
        Map<String, String> target = new HashMap<>();
        provider.populateMetadata(target);
        assertThat(target).containsEntry("customizer-1", "val1");
    }

    @Test
    @DisplayName("测试 DefaultNacosMetadataCustomizer 默认定制行为")
    void testDefaultNacosMetadataCustomizer() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getMetadata().getCustom().put("service-type", "internal");

        io.github.atengk.cloud.nacos.spi.DefaultNacosMetadataCustomizer customizer =
                new io.github.atengk.cloud.nacos.spi.DefaultNacosMetadataCustomizer(properties);

        Map<String, String> map = new HashMap<>();
        customizer.customize(map);

        assertThat(map).containsEntry("framework", "ateng-cloud");
        assertThat(map).containsEntry("service-type", "internal");
    }
}
