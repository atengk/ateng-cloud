package io.github.atengk.cloud.sample.nacos.model;

/**
 * 业务风控与交易规则动态强类型配置模型
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class TradeRuleConfig {

    private String ruleName;
    private int maxLimit;
    private boolean enabled;

    public TradeRuleConfig() {
    }

    public TradeRuleConfig(String ruleName, int maxLimit, boolean enabled) {
        this.ruleName = ruleName;
        this.maxLimit = maxLimit;
        this.enabled = enabled;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public int getMaxLimit() {
        return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
