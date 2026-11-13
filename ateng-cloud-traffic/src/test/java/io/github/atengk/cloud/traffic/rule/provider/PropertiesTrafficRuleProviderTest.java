package io.github.atengk.cloud.traffic.rule.provider;

import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基于本地配置文件静态流量分流规则提供者单元测试
 *
 * @author Ateng
 * @since 2026-11-13
 */
class PropertiesTrafficRuleProviderTest {

    @Test
    @DisplayName("未配置本地规则或构造入参为 null 时安全返回空不可变列表")
    void testEmptyAndNullSafety() {
        PropertiesTrafficRuleProvider nullPropsProvider = new PropertiesTrafficRuleProvider(null);
        assertThat(nullPropsProvider.getRules()).isEmpty();
        assertThat(nullPropsProvider.getActiveRules()).isEmpty();

        AtengTrafficProperties emptyProps = new AtengTrafficProperties();
        PropertiesTrafficRuleProvider emptyProvider = new PropertiesTrafficRuleProvider(emptyProps);
        assertThat(emptyProvider.getRules()).isEmpty();
    }

    @Test
    @DisplayName("正确读取本地静态规则，过滤空元素并返回不可变防御性快照")
    void testGetRulesFilteringAndUnmodifiable() {
        AtengTrafficProperties properties = new AtengTrafficProperties();
        TrafficRule rule1 = TrafficRule.builder().id("local-r1").priority(10).targetTag("canary-a").build();
        TrafficRule rule2 = TrafficRule.builder().id("local-r2").priority(20).targetTag("canary-b").build();

        List<TrafficRule> rawList = new ArrayList<>();
        rawList.add(rule1);
        rawList.add(null);
        rawList.add(rule2);
        properties.setRules(rawList);

        PropertiesTrafficRuleProvider provider = new PropertiesTrafficRuleProvider(properties);
        List<TrafficRule> rules = provider.getRules();

        assertThat(rules).hasSize(2);
        assertThat(rules).extracting(TrafficRule::getId).containsExactly("local-r1", "local-r2");
        assertThatThrownBy(() -> rules.add(rule1))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
