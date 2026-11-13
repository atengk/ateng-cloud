package io.github.atengk.cloud.traffic.rule.provider;

import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;

import java.util.List;

/**
 * 基于本地配置文件 {@code ateng.cloud.traffic.rules} 的静态流量分流规则提供者
 * <p>
 * 当未启用 Nacos 配置中心或未注册自定义 {@link TrafficRuleProvider} 时作为缺省规则源兜底生效。
 *
 * @author Ateng
 * @since 2026-11-13
 */
public class PropertiesTrafficRuleProvider implements TrafficRuleProvider {

    private final AtengTrafficProperties properties;

    /**
     * 构造基于配置属性的静态流量规则提供者
     *
     * @param properties 流量治理配置属性
     */
    public PropertiesTrafficRuleProvider(AtengTrafficProperties properties) {
        this.properties = properties != null ? properties : new AtengTrafficProperties();
    }

    @Override
    public List<TrafficRule> getRules() {
        return properties.resolveLocalRules();
    }
}
