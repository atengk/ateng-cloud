package io.github.atengk.cloud.seata.autoconfigure;

import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分布式事务增强自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengSeataAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSeataAutoConfiguration.class));

    @Test
    @DisplayName("测试默认配置下自动装配核心组件与默认属性")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengSeataAutoConfiguration.class);
            assertThat(context).hasSingleBean(AtengSeataAutoConfiguration.SeataMarker.class);
            assertThat(context).hasSingleBean(SeataProperties.class);

            SeataProperties properties = context.getBean(SeataProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getTx().getDefaultTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getLock().getRetryInterval()).isEqualTo(Duration.ofMillis(50));
            assertThat(properties.getLock().getRetryTimes()).isEqualTo(30);
            assertThat(properties.getDegrade().isEnabled()).isFalse();
            assertThat(properties.getTcc().isFenceEnabled()).isTrue();
        });
    }

    @Test
    @DisplayName("测试配置 ateng.cloud.seata.enabled=false 时装配类条件跳过")
    void testDisabledConfiguration() {
        contextRunner
                .withPropertyValues("ateng.cloud.seata.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengSeataAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(AtengSeataAutoConfiguration.SeataMarker.class);
                });
    }

    @Test
    @DisplayName("测试自定义 SeataMarker 时遵循 @ConditionalOnMissingBean 守卫")
    void testCustomMarkerOverride() {
        SeataProperties customProps = new SeataProperties();
        AtengSeataAutoConfiguration.SeataMarker customMarker = new AtengSeataAutoConfiguration.SeataMarker(customProps);

        contextRunner
                .withBean("customSeataMarker", AtengSeataAutoConfiguration.SeataMarker.class, () -> customMarker)
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengSeataAutoConfiguration.SeataMarker.class);
                    assertThat(context.getBean(AtengSeataAutoConfiguration.SeataMarker.class)).isSameAs(customMarker);
                });
    }
}
