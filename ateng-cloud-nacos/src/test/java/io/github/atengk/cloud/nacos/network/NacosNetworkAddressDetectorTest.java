package io.github.atengk.cloud.nacos.network;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网卡智能清洗与 IP 选择器单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class NacosNetworkAddressDetectorTest {

    private final NacosNetworkAddressDetector detector = new NacosNetworkAddressDetector();

    @Test
    @DisplayName("测试常见虚拟网卡黑名单模式匹配")
    void testIgnoredInterfaceMatching() {
        List<String> ignoredPatterns = Arrays.asList(
                "docker0", "flannel.*", "cni.*", "veth.*", "vmnet.*", "virbr.*", "br-.*"
        );

        assertThat(detector.isIgnoredInterface("docker0", ignoredPatterns)).isTrue();
        assertThat(detector.isIgnoredInterface("DOCKER0", ignoredPatterns)).isTrue();
        assertThat(detector.isIgnoredInterface("flannel.1", ignoredPatterns)).isTrue();
        assertThat(detector.isIgnoredInterface("cni0", ignoredPatterns)).isTrue();
        assertThat(detector.isIgnoredInterface("veth12345", ignoredPatterns)).isTrue();
        assertThat(detector.isIgnoredInterface("br-9876", ignoredPatterns)).isTrue();
        assertThat(detector.isIgnoredInterface("vmnet8", ignoredPatterns)).isTrue();
        assertThat(detector.isIgnoredInterface("VirtualBox Host-Only Ethernet Adapter", Arrays.asList(".*vbox.*", ".*virtualbox.*"))).isTrue();
        assertThat(detector.isIgnoredInterface("vEthernet (FSE HostVnic)", Arrays.asList(".*vethernet.*"))).isTrue();

        // 真实物理网卡绝不能被误伤忽略
        assertThat(detector.isIgnoredInterface("eth0", ignoredPatterns)).isFalse();
        assertThat(detector.isIgnoredInterface("ens33", ignoredPatterns)).isFalse();
        assertThat(detector.isIgnoredInterface("en0", ignoredPatterns)).isFalse();
        assertThat(detector.isIgnoredInterface("wlan0", ignoredPatterns)).isFalse();
    }

    @Test
    @DisplayName("测试在当前运行环境中能侦测到有效的非回环或安全保底 IP")
    void testDetectIpInCurrentEnvironment() {
        AtengNacosProperties.NetworkProperties props = new AtengNacosProperties.NetworkProperties();
        props.setFailOnLoopback(false);

        String ip = detector.detectIp(props);

        assertThat(ip).isNotBlank();
        // 校验返回的是有效的 IPv4 格式
        assertThat(ip.split("\\.")).hasSize(4);
    }

    @Test
    @DisplayName("测试指定不存在网卡时优雅降级自动侦测")
    void testFallbackWhenSpecifiedInterfaceNotFound() {
        AtengNacosProperties.NetworkProperties props = new AtengNacosProperties.NetworkProperties();
        props.setNetworkInterface("non_existent_interface_xyz_999");
        props.setFailOnLoopback(false);

        String ip = detector.detectIp(props);

        assertThat(ip).isNotBlank();
        assertThat(ip.split("\\.")).hasSize(4);
    }

    @Test
    @DisplayName("测试当全部网卡被过滤仅剩回环且开启 failOnLoopback 时强校验 Fail-Fast 阻断")
    void testFailFastWhenOnlyLoopbackAvailable() {
        AtengNacosProperties.NetworkProperties props = new AtengNacosProperties.NetworkProperties();
        props.setIgnoredInterfaces(Collections.singletonList(".*")); // 忽略所有网卡强制回环
        props.setFailOnLoopback(true);

        assertThatThrownBy(() -> detector.detectIp(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-on-loopback is enabled");
    }
}
