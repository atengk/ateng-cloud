package io.github.atengk.cloud.traffic.rule.provider;

import io.github.atengk.cloud.traffic.rule.model.TrafficRule;

import java.util.List;

/**
 * 流量规则提供者 SPI 契约
 * <p>
 * 解耦规则存储源（如 Nacos 配置中心、本地静态配置、外部控制面），为决策引擎提供统一的规则集合读取视图。
 *
 * @author Ateng
 * @since 2026-10-29
 */
public interface TrafficRuleProvider {

    /**
     * 获取当前生效的流量分流规则快照
     *
     * @return 规则列表（保底返回空列表，严禁返回 null）
     */
    List<TrafficRule> getRules();

    /**
     * 获取当前处于启用状态的规则快照
     *
     * @return 启用的规则列表（保底返回空列表，严禁返回 null）
     */
    default List<TrafficRule> getActiveRules() {
        List<TrafficRule> rules = getRules();
        if (rules == null || rules.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return rules.stream().filter(r -> r != null && r.isEnabled()).toList();
    }
}
