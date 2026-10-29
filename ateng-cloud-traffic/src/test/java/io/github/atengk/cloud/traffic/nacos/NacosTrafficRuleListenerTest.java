package io.github.atengk.cloud.traffic.nacos;

import com.alibaba.nacos.api.config.listener.Listener;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.properties.TrafficNacosProperties;
import io.github.atengk.cloud.traffic.rule.model.TrafficCondition;
import io.github.atengk.cloud.traffic.rule.model.TrafficFieldSource;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Nacos 动态流量分流规则监听器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@ExtendWith(MockitoExtension.class)
class NacosTrafficRuleListenerTest {

    @Mock
    private NacosTemplate nacosTemplate;

    @Mock
    private ObjectProvider<NacosTemplate> nacosTemplateProvider;

    private AtengTrafficProperties properties;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        properties = new AtengTrafficProperties();
        TrafficNacosProperties nacosProps = new TrafficNacosProperties();
        nacosProps.setEnabled(true);
        nacosProps.setGroup("DEFAULT_GROUP");
        nacosProps.setGlobalDataId("traffic-rules.json");
        properties.setNacos(nacosProps);

        environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "order-service");
    }

    @Test
    @DisplayName("离线与 Nacos 不可用时平滑降级自洽测试")
    void testOfflineHermeticResilience() {
        // 1. nacosTemplateProvider 为空
        when(nacosTemplateProvider.getIfAvailable()).thenReturn(null);
        NacosTrafficRuleListener listener1 = new NacosTrafficRuleListener(nacosTemplateProvider, properties, environment);
        listener1.afterPropertiesSet();
        assertThat(listener1.isInitialized()).isFalse();
        assertThat(listener1.getRules()).isEmpty();

        // 2. isConfigAvailable 返回 false
        when(nacosTemplateProvider.getIfAvailable()).thenReturn(nacosTemplate);
        when(nacosTemplate.isConfigAvailable()).thenReturn(false);
        NacosTrafficRuleListener listener2 = new NacosTrafficRuleListener(nacosTemplateProvider, properties, environment);
        listener2.afterPropertiesSet();
        assertThat(listener2.isInitialized()).isFalse();
        assertThat(listener2.getRules()).isEmpty();
    }

    @Test
    @DisplayName("初始加载与应用专属规则优先覆盖测试")
    void testInitialLoadAndAppRulePrecedence() {
        when(nacosTemplateProvider.getIfAvailable()).thenReturn(nacosTemplate);
        when(nacosTemplate.isConfigAvailable()).thenReturn(true);

        TrafficRule appRule = TrafficRule.builder()
                .id("rule-app-v2")
                .name("应用专属灰度规则")
                .targetTag("app-gray")
                .priority(1)
                .build();
        TrafficRule globalRule = TrafficRule.builder()
                .id("rule-global-v1")
                .name("全局通用灰度规则")
                .targetTag("global-gray")
                .priority(10)
                .build();

        when(nacosTemplate.findConfig("order-service-traffic-rules.json", "DEFAULT_GROUP"))
                .thenReturn(Optional.of(JsonUtil.toJsonString(List.of(appRule))));
        when(nacosTemplate.findConfig("traffic-rules.json", "DEFAULT_GROUP"))
                .thenReturn(Optional.of(JsonUtil.toJsonString(List.of(globalRule))));

        NacosTrafficRuleListener listener = new NacosTrafficRuleListener(nacosTemplateProvider, properties, environment);
        listener.afterPropertiesSet();

        assertThat(listener.isInitialized()).isTrue();
        assertThat(listener.getResolvedAppDataId()).isEqualTo("order-service-traffic-rules.json");
        assertThat(listener.getResolvedGlobalDataId()).isEqualTo("traffic-rules.json");

        // 优先采用应用专属规则
        List<TrafficRule> activeRules = listener.getRules();
        assertThat(activeRules).hasSize(1);
        assertThat(activeRules.get(0).getId()).isEqualTo("rule-app-v2");
        assertThat(activeRules.get(0).getTargetTag()).isEqualTo("app-gray");
    }

    @Test
    @DisplayName("动态推送更新与无专属规则时回退全局规则测试")
    void testDynamicPushAndFallbackToGlobal() {
        when(nacosTemplateProvider.getIfAvailable()).thenReturn(nacosTemplate);
        when(nacosTemplate.isConfigAvailable()).thenReturn(true);

        when(nacosTemplate.findConfig(anyString(), anyString())).thenReturn(Optional.empty());

        NacosTrafficRuleListener listener = new NacosTrafficRuleListener(nacosTemplateProvider, properties, environment);
        listener.afterPropertiesSet();
        assertThat(listener.getRules()).isEmpty();

        // 1. 全局配置变更推送
        TrafficRule globalRule = TrafficRule.builder()
                .id("rule-global-new")
                .targetTag("global-tag")
                .build();
        listener.receiveGlobalConfig(JsonUtil.toJsonString(List.of(globalRule)));

        assertThat(listener.getRules()).hasSize(1);
        assertThat(listener.getRules().get(0).getTargetTag()).isEqualTo("global-tag");

        // 2. 应用专属配置推送 (优先覆盖)
        TrafficRule appRule = TrafficRule.builder()
                .id("rule-app-new")
                .targetTag("app-tag")
                .build();
        listener.receiveAppConfig(JsonUtil.toJsonString(List.of(appRule)));

        assertThat(listener.getRules()).hasSize(1);
        assertThat(listener.getRules().get(0).getTargetTag()).isEqualTo("app-tag");

        // 3. 应用专属配置清空下线，自动平滑回退至全局规则
        listener.receiveAppConfig("[]");
        assertThat(listener.getRules()).hasSize(1);
        assertThat(listener.getRules().get(0).getTargetTag()).isEqualTo("global-tag");
    }

    @Test
    @DisplayName("MERGE 合并策略测试 (应用规则与全局规则共存并按 priority 排序)")
    void testMergeStrategy() {
        properties.getNacos().setMergeStrategy("MERGE");

        when(nacosTemplateProvider.getIfAvailable()).thenReturn(nacosTemplate);
        when(nacosTemplate.isConfigAvailable()).thenReturn(true);
        when(nacosTemplate.findConfig(anyString(), anyString())).thenReturn(Optional.empty());

        NacosTrafficRuleListener listener = new NacosTrafficRuleListener(nacosTemplateProvider, properties, environment);
        listener.afterPropertiesSet();

        TrafficRule rule1 = TrafficRule.builder().id("r1").priority(20).targetTag("t1").build();
        TrafficRule rule2 = TrafficRule.builder().id("r2").priority(5).targetTag("t2").build();

        listener.receiveAppConfig(JsonUtil.toJsonString(List.of(rule1)));
        listener.receiveGlobalConfig(JsonUtil.toJsonString(List.of(rule2)));

        List<TrafficRule> rules = listener.getRules();
        assertThat(rules).hasSize(2);
        // 按 priority 升序排序：r2 (5) 排在 r1 (20) 之前
        assertThat(rules.get(0).getId()).isEqualTo("r2");
        assertThat(rules.get(1).getId()).isEqualTo("r1");
    }

    @Test
    @DisplayName("JSON 格式非法时保持既有快照不崩溃测试")
    void testInvalidJsonSyntaxResilience() {
        when(nacosTemplateProvider.getIfAvailable()).thenReturn(nacosTemplate);
        when(nacosTemplate.isConfigAvailable()).thenReturn(true);

        TrafficRule validRule = TrafficRule.builder().id("valid-1").targetTag("v1").build();
        when(nacosTemplate.findConfig("order-service-traffic-rules.json", "DEFAULT_GROUP"))
                .thenReturn(Optional.of(JsonUtil.toJsonString(List.of(validRule))));
        when(nacosTemplate.findConfig("traffic-rules.json", "DEFAULT_GROUP"))
                .thenReturn(Optional.empty());

        NacosTrafficRuleListener listener = new NacosTrafficRuleListener(nacosTemplateProvider, properties, environment);
        listener.afterPropertiesSet();
        assertThat(listener.getRules()).hasSize(1);

        // 推送损坏的 JSON 文本
        listener.receiveAppConfig("{ bad json !!!");

        // 必须保全原先生效的快照，不发生 NPE 或规则丢弃
        assertThat(listener.getRules()).hasSize(1);
        assertThat(listener.getRules().get(0).getId()).isEqualTo("valid-1");
    }

    @Test
    @DisplayName("destroy 销毁时幂等移除 Nacos 监听器测试")
    void testDestroyCleanup() {
        when(nacosTemplateProvider.getIfAvailable()).thenReturn(nacosTemplate);
        when(nacosTemplate.isConfigAvailable()).thenReturn(true);
        when(nacosTemplate.findConfig(anyString(), anyString())).thenReturn(Optional.empty());

        NacosTrafficRuleListener listener = new NacosTrafficRuleListener(nacosTemplateProvider, properties, environment);
        listener.afterPropertiesSet();

        listener.destroy();

        verify(nacosTemplate).removeConfigListener(eq("order-service-traffic-rules.json"), eq("DEFAULT_GROUP"), any(Listener.class));
        verify(nacosTemplate).removeConfigListener(eq("traffic-rules.json"), eq("DEFAULT_GROUP"), any(Listener.class));
    }
}
