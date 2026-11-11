package io.github.atengk.cloud.sample.nacos.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多级动态交易风控规则列表视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public class TradeRuleListVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前生效的多级交易规则列表
     */
    private final List<TradeRuleConfig> rules;

    /**
     * 规则条目总数
     */
    private final int count;

    /**
     * 最近一次规则变更通知时的链路追踪标识
     */
    private final String traceId;

    /**
     * 累计规则列表变更通知次数
     */
    private final int updateCount;

    public TradeRuleListVO(List<TradeRuleConfig> rules, String traceId, int updateCount) {
        this.rules = rules != null ? Collections.unmodifiableList(new ArrayList<>(rules)) : Collections.emptyList();
        this.count = this.rules.size();
        this.traceId = traceId != null ? traceId : "";
        this.updateCount = updateCount;
    }

    public List<TradeRuleConfig> getRules() {
        return rules;
    }

    public int getCount() {
        return count;
    }

    public String getTraceId() {
        return traceId;
    }

    public int getUpdateCount() {
        return updateCount;
    }
}
