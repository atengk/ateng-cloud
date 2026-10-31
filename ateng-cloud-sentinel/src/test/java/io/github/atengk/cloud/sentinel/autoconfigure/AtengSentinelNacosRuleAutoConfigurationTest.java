package io.github.atengk.cloud.sentinel.autoconfigure;

import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
import com.alibaba.csp.sentinel.property.DynamicSentinelProperty;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import io.github.atengk.cloud.sentinel.datasource.NacosDataSourceFactory;
import io.github.atengk.cloud.sentinel.datasource.SentinelNacosRuleSubscriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sentinel Nacos 规则自动装配测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengSentinelNacosRuleAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengSentinelAutoConfiguration.class,
                    AtengSentinelNacosRuleAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        // 上下文零污染铁律：彻底恢复 Sentinel RuleManager 静态委托属性与内存规则
        FlowRuleManager.register2Property(new DynamicSentinelProperty<>());
        DegradeRuleManager.register2Property(new DynamicSentinelProperty<>());
        ParamFlowRuleManager.register2Property(new DynamicSentinelProperty<>());
        SystemRuleManager.register2Property(new DynamicSentinelProperty<>());
        AuthorityRuleManager.register2Property(new DynamicSentinelProperty<>());

        FlowRuleManager.loadRules(Collections.emptyList());
        DegradeRuleManager.loadRules(Collections.emptyList());
        ParamFlowRuleManager.loadRules(Collections.emptyList());
        SystemRuleManager.loadRules(Collections.emptyList());
        AuthorityRuleManager.loadRules(Collections.emptyList());
    }

    @Test
    @DisplayName("默认情况下(未配enabled)不装配 Nacos 规则订阅器(离线自洽守卫)")
    void shouldNotLoadWhenDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(SentinelNacosRuleSubscriber.class);
            assertThat(context).doesNotHaveBean(NacosDataSourceFactory.class);
        });
    }

    @Test
    @DisplayName("显式设置 enabled=false 时不装配")
    void shouldNotLoadWhenExplicitlyDisabled() {
        contextRunner
                .withPropertyValues("ateng.cloud.sentinel.nacos-rules.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SentinelNacosRuleSubscriber.class);
                    assertThat(context).doesNotHaveBean(NacosDataSourceFactory.class);
                });
    }

    @Test
    @DisplayName("当 enabled=true 且提供 mock 工厂时正常装配 Subscriber 与 Factory")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldLoadWhenEnabledWithMockFactory() {
        NacosDataSourceFactory customFactory = mock(NacosDataSourceFactory.class);
        ReadableDataSource mockDs = mock(ReadableDataSource.class);
        SentinelProperty mockProperty = new DynamicSentinelProperty();
        when(mockDs.getProperty()).thenReturn(mockProperty);
        when(customFactory.createDataSource(any(), any(), any(), any())).thenReturn(mockDs);

        contextRunner
                .withPropertyValues(
                        "ateng.cloud.sentinel.nacos-rules.enabled=true",
                        "ateng.cloud.sentinel.nacos-rules.server-addr=127.0.0.1:8848",
                        "spring.application.name=demo-service"
                )
                .withBean(NacosDataSourceFactory.class, () -> customFactory)
                .run(context -> {
                    assertThat(context).hasSingleBean(SentinelNacosRuleSubscriber.class);
                    assertThat(context).hasSingleBean(NacosDataSourceFactory.class);
                    // 验证下游用户自定义 Bean 优先覆盖默认工厂
                    assertThat(context.getBean(NacosDataSourceFactory.class)).isSameAs(customFactory);
                });
    }
}
