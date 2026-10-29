package io.github.atengk.cloud.traffic.autoconfigure;

import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.github.atengk.cloud.traffic.nacos.NacosTrafficRuleListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Nacos 流量规则自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengTrafficNacosAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengTrafficAutoConfiguration.class,
                    AtengTrafficNacosAutoConfiguration.class
            ));

    @Test
    @DisplayName("提供 NacosTemplate 时成功装配 NacosTrafficRuleListener")
    void testAutoConfigurationActive() {
        NacosTemplate mockNacosTemplate = mock(NacosTemplate.class);
        contextRunner
                .withBean(NacosTemplate.class, () -> mockNacosTemplate)
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengTrafficNacosAutoConfiguration.class);
                    assertThat(context).hasSingleBean(NacosTrafficRuleListener.class);
                });
    }

    @Test
    @DisplayName("配置 ateng.cloud.traffic.nacos.enabled=false 时装配类条件跳过")
    void testDisabledConfiguration() {
        NacosTemplate mockNacosTemplate = mock(NacosTemplate.class);
        contextRunner
                .withBean(NacosTemplate.class, () -> mockNacosTemplate)
                .withPropertyValues("ateng.cloud.traffic.nacos.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengTrafficNacosAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(NacosTrafficRuleListener.class);
                });
    }

    @Test
    @DisplayName("下游自定义 NacosTrafficRuleListener Bean 时平滑接管")
    void testCustomBeanTakeover() {
        NacosTrafficRuleListener customListener = mock(NacosTrafficRuleListener.class);
        NacosTemplate mockNacosTemplate = mock(NacosTemplate.class);

        contextRunner
                .withBean(NacosTemplate.class, () -> mockNacosTemplate)
                .withBean("customListener", NacosTrafficRuleListener.class, () -> customListener)
                .run(context -> {
                    assertThat(context).hasSingleBean(NacosTrafficRuleListener.class);
                    assertThat(context.getBean(NacosTrafficRuleListener.class)).isSameAs(customListener);
                });
    }
}
