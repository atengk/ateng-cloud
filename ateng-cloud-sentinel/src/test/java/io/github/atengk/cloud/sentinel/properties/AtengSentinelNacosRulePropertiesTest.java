package io.github.atengk.cloud.sentinel.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sentinel Nacos 规则配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengSentinelNacosRulePropertiesTest {

    @Test
    @DisplayName("测试 Nacos 规则默认属性与安全开闭契约")
    void shouldHaveSafeDefaultProperties() {
        AtengSentinelNacosRuleProperties properties = new AtengSentinelNacosRuleProperties();

        // 默认离线自洽安全关闭
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getServerAddr()).isEqualTo("127.0.0.1:8848");
        assertThat(properties.getNamespace()).isEmpty();
        assertThat(properties.getGroupId()).isEqualTo("SENTINEL_GROUP");
        assertThat(properties.getDataIdPrefix()).isNull();

        // 5 大规则全家桶默认开启订阅且后缀规范对齐
        assertThat(properties.getFlow().isEnabled()).isTrue();
        assertThat(properties.getFlow().getDataIdSuffix()).isEqualTo("-flow.json");

        assertThat(properties.getDegrade().isEnabled()).isTrue();
        assertThat(properties.getDegrade().getDataIdSuffix()).isEqualTo("-degrade.json");

        assertThat(properties.getParamFlow().isEnabled()).isTrue();
        assertThat(properties.getParamFlow().getDataIdSuffix()).isEqualTo("-param-flow.json");

        assertThat(properties.getSystem().isEnabled()).isTrue();
        assertThat(properties.getSystem().getDataIdSuffix()).isEqualTo("-system.json");

        assertThat(properties.getAuthority().isEnabled()).isTrue();
        assertThat(properties.getAuthority().getDataIdSuffix()).isEqualTo("-authority.json");
    }

    @Test
    @DisplayName("测试配置转换为 Nacos 原生 Properties")
    void shouldConvertCorrectlyToNacosProperties() {
        AtengSentinelNacosRuleProperties properties = new AtengSentinelNacosRuleProperties();
        properties.setServerAddr("192.168.1.100:8848");
        properties.setNamespace("test-namespace");
        properties.setUsername("nacos");
        properties.setPassword("nacos-password");
        properties.setContextPath("/nacos");
        properties.setAccessKey("ak-123");
        properties.setSecretKey("sk-456");

        Properties nacosProps = properties.toNacosProperties();
        assertThat(nacosProps.getProperty("serverAddr")).isEqualTo("192.168.1.100:8848");
        assertThat(nacosProps.getProperty("namespace")).isEqualTo("test-namespace");
        assertThat(nacosProps.getProperty("username")).isEqualTo("nacos");
        assertThat(nacosProps.getProperty("password")).isEqualTo("nacos-password");
        assertThat(nacosProps.getProperty("contextPath")).isEqualTo("/nacos");
        assertThat(nacosProps.getProperty("accessKey")).isEqualTo("ak-123");
        assertThat(nacosProps.getProperty("secretKey")).isEqualTo("sk-456");
    }
}
