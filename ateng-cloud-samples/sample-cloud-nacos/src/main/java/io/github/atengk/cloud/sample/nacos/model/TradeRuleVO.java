package io.github.atengk.cloud.sample.nacos.model;

/**
 * 动态交易风控规则视图对象
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class TradeRuleVO {

    private String ruleName;
    private int maxLimit;
    private boolean enabled;
    private String traceId;
    private int updateCount;

    public TradeRuleVO() {
    }

    public TradeRuleVO(String ruleName, int maxLimit, boolean enabled, String traceId, int updateCount) {
        this.ruleName = ruleName;
        this.maxLimit = maxLimit;
        this.enabled = enabled;
        this.traceId = traceId;
        this.updateCount = updateCount;
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

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public int getUpdateCount() {
        return updateCount;
    }

    public void setUpdateCount(int updateCount) {
        this.updateCount = updateCount;
    }
}
