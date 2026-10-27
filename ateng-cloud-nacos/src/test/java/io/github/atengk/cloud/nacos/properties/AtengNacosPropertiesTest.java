package io.github.atengk.cloud.nacos.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nacos 配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class AtengNacosPropertiesTest {

    @Test
    @DisplayName("测试默认配置属性基线符合生产标准")
    void testDefaultValues() {
        AtengNacosProperties props = new AtengNacosProperties();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getDrainTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.getZone()).isEmpty();
        assertThat(props.getCluster()).isEmpty();

        // 默认元数据配置
        AtengNacosProperties.MetadataProperties meta = props.getMetadata();
        assertThat(meta.isStartupTime()).isTrue();
        assertThat(meta.getWarmupDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(meta.getVersion()).isEmpty();
        assertThat(meta.getEnv()).isEmpty();
        assertThat(meta.getTrafficTag()).isEmpty();
        assertThat(meta.getCustom()).isEmpty();

        // 默认网络配置
        AtengNacosProperties.NetworkProperties net = props.getNetwork();
        assertThat(net.isFailOnLoopback()).isTrue();
        assertThat(net.getNetworkInterface()).isEmpty();
        assertThat(net.getIgnoredInterfaces()).contains("docker0");

        // 默认端点配置
        AtengNacosProperties.EndpointProperties ep = props.getEndpoint();
        assertThat(ep.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("测试强类型 Duration 与嵌套属性增量热绑")
    void testPropertyBinding() {
        Map<String, Object> map = new HashMap<>();
        map.put("ateng.cloud.nacos.enabled", "true");
        map.put("ateng.cloud.nacos.drain-timeout", "15s");
        map.put("ateng.cloud.nacos.zone", "beijing-az1");
        map.put("ateng.cloud.nacos.metadata.startup-time", "true");
        map.put("ateng.cloud.nacos.metadata.warmup-duration", "120s");
        map.put("ateng.cloud.nacos.metadata.version", "2.1.0");
        map.put("ateng.cloud.nacos.metadata.custom.region", "cn-north-1");
        map.put("ateng.cloud.nacos.network.fail-on-loopback", "true");
        map.put("ateng.cloud.nacos.network.preferred-networks[0]", "10.0.");

        ConfigurationPropertySource source = new MapConfigurationPropertySource(map);
        Binder binder = new Binder(source);

        AtengNacosProperties props = binder.bind("ateng.cloud.nacos", Bindable.of(AtengNacosProperties.class)).get();

        assertThat(props.getDrainTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(props.getZone()).isEqualTo("beijing-az1");
        assertThat(props.getMetadata().getWarmupDuration()).isEqualTo(Duration.ofSeconds(120));
        assertThat(props.getMetadata().getVersion()).isEqualTo("2.1.0");
        assertThat(props.getMetadata().getCustom()).containsEntry("region", "cn-north-1");
        assertThat(props.getNetwork().isFailOnLoopback()).isTrue();
        assertThat(props.getNetwork().getPreferredNetworks()).containsExactly("10.0.");
    }
}
