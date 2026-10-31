package io.github.atengk.cloud.sentinel.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

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
    @DisplayName("测试显式配置转换为 Nacos 原生 Properties")
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

    @Test
    @DisplayName("测试 Nacos 连接参数四级平滑自适应继承时序")
    void shouldInheritNacosPropertiesAcrossFourTiers() {
        // 场景 1: 未配置任何参数时，回退至第四级保底默认值
        AtengSentinelNacosRuleProperties propsDefault = new AtengSentinelNacosRuleProperties();
        MockEnvironment envEmpty = new MockEnvironment();
        Properties nacosPropsDefault = propsDefault.toNacosProperties(envEmpty);
        assertThat(nacosPropsDefault.getProperty("serverAddr")).isEqualTo("127.0.0.1:8848");
        assertThat(nacosPropsDefault.getProperty("namespace")).isNull();

        // 场景 2: 环境中配置了 spring.cloud.nacos.discovery.* (第三级)，成功继承
        MockEnvironment envDiscovery = new MockEnvironment();
        envDiscovery.setProperty("spring.cloud.nacos.discovery.server-addr", "10.0.0.3:8848");
        envDiscovery.setProperty("spring.cloud.nacos.discovery.namespace", "ns-discovery");
        envDiscovery.setProperty("spring.cloud.nacos.discovery.username", "user-discovery");
        Properties nacosPropsDiscovery = propsDefault.toNacosProperties(envDiscovery);
        assertThat(nacosPropsDiscovery.getProperty("serverAddr")).isEqualTo("10.0.0.3:8848");
        assertThat(nacosPropsDiscovery.getProperty("namespace")).isEqualTo("ns-discovery");
        assertThat(nacosPropsDiscovery.getProperty("username")).isEqualTo("user-discovery");

        // 场景 3: 环境中同时配置了 spring.cloud.nacos.config.* (第二级) 与 discovery (第三级)，第二级胜出
        MockEnvironment envConfig = new MockEnvironment();
        envConfig.setProperty("spring.cloud.nacos.config.server-addr", "10.0.0.2:8848");
        envConfig.setProperty("spring.cloud.nacos.config.namespace", "ns-config");
        envConfig.setProperty("spring.cloud.nacos.discovery.server-addr", "10.0.0.3:8848");
        envConfig.setProperty("spring.cloud.nacos.discovery.namespace", "ns-discovery");
        Properties nacosPropsConfig = propsDefault.toNacosProperties(envConfig);
        assertThat(nacosPropsConfig.getProperty("serverAddr")).isEqualTo("10.0.0.2:8848");
        assertThat(nacosPropsConfig.getProperty("namespace")).isEqualTo("ns-config");

        // 场景 4: 显式声明 ateng.cloud.sentinel.nacos-rules (第一级)，第一级最高优先级压制第二/三级
        AtengSentinelNacosRuleProperties propsExplicit = new AtengSentinelNacosRuleProperties();
        propsExplicit.setServerAddr("10.0.0.1:8848");
        propsExplicit.setNamespace("ns-explicit");
        Properties nacosPropsExplicit = propsExplicit.toNacosProperties(envConfig);
        assertThat(nacosPropsExplicit.getProperty("serverAddr")).isEqualTo("10.0.0.1:8848");
        assertThat(nacosPropsExplicit.getProperty("namespace")).isEqualTo("ns-explicit");
    }
}
