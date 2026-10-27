package io.github.atengk.cloud.nacos.config;

import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import io.github.atengk.cloud.nacos.network.NacosNetworkAddressDetector;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配行为与条件装配守卫单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class AtengNacosAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengNacosAutoConfiguration.class));

    @Test
    @DisplayName("测试默认配置下核心 Bean 成功按需装配")
    void testDefaultAutoconfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengNacosProperties.class);
            assertThat(context).hasSingleBean(NacosNetworkAddressDetector.class);
            assertThat(context).hasSingleBean(NacosMetadataProvider.class);
            assertThat(context).hasSingleBean(NacosDiscoveryPropertiesPostProcessor.class);
        });
    }

    @Test
    @DisplayName("测试显式禁用 ateng.cloud.nacos.enabled=false 时装配静默关闭")
    void testDisabledAutoconfiguration() {
        contextRunner
                .withPropertyValues("ateng.cloud.nacos.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengNacosProperties.class);
                    assertThat(context).doesNotHaveBean(NacosNetworkAddressDetector.class);
                    assertThat(context).doesNotHaveBean(NacosMetadataProvider.class);
                });
    }
}
