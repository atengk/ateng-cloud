package io.github.atengk.cloud.traffic.rule.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 流量染色与金丝雀灰度分流规则模型
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficRule implements Serializable, Comparable<TrafficRule> {

    private static final long serialVersionUID = 1L;

    /**
     * 规则唯一标识
     */
    private String id;

    /**
     * 规则可读名称
     */
    private String name;

    /**
     * 规则优先级 (数值越小优先级越高，同优先级按定义顺序)
     */
    private int priority = 0;

    /**
     * 规则匹配成功后赋予的流量染色标签 (如 "gray", "v2")
     */
    private String targetTag;

    /**
     * 条件之间的逻辑关系 (默认 AND)
     */
    private TrafficConditionRelation conditionRelation = TrafficConditionRelation.AND;

    /**
     * 谓词匹配条件列表
     */
    private List<TrafficCondition> conditions = new ArrayList<>();

    /**
     * 百分比粘性切流阈值 (0~100，为 null 或 100 时全量放行)
     */
    private Integer percentage;

    /**
     * 百分比切流哈希种子来源 (默认 USER_ID)
     */
    private TrafficFieldSource hashKeySource = TrafficFieldSource.USER_ID;

    /**
     * 当哈希种子来源为 HEADER、QUERY 或 COOKIE 时的指定键名
     */
    private String hashKeyName;

    /**
     * 规则是否启用 (默认启用)
     */
    private boolean enabled = true;

    public TrafficRule() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getTargetTag() {
        return targetTag;
    }

    public void setTargetTag(String targetTag) {
        this.targetTag = targetTag;
    }

    public TrafficConditionRelation getConditionRelation() {
        return conditionRelation;
    }

    public void setConditionRelation(TrafficConditionRelation conditionRelation) {
        this.conditionRelation = conditionRelation;
    }

    public List<TrafficCondition> getConditions() {
        return conditions != null ? Collections.unmodifiableList(conditions) : Collections.emptyList();
    }

    public void setConditions(List<TrafficCondition> conditions) {
        this.conditions = conditions != null ? new ArrayList<>(conditions) : new ArrayList<>();
    }

    public Integer getPercentage() {
        return percentage;
    }

    public void setPercentage(Integer percentage) {
        this.percentage = percentage;
    }

    public TrafficFieldSource getHashKeySource() {
        return hashKeySource;
    }

    public void setHashKeySource(TrafficFieldSource hashKeySource) {
        this.hashKeySource = hashKeySource;
    }

    public String getHashKeyName() {
        return hashKeyName;
    }

    public void setHashKeyName(String hashKeyName) {
        this.hashKeyName = hashKeyName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public int compareTo(TrafficRule o) {
        if (o == null) {
            return -1;
        }
        return Integer.compare(this.priority, o.priority);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TrafficRule that)) {
            return false;
        }
        return priority == that.priority
                && enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(targetTag, that.targetTag)
                && conditionRelation == that.conditionRelation
                && Objects.equals(conditions, that.conditions)
                && Objects.equals(percentage, that.percentage)
                && hashKeySource == that.hashKeySource
                && Objects.equals(hashKeyName, that.hashKeyName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, priority, targetTag, conditionRelation, conditions, percentage, hashKeySource, hashKeyName, enabled);
    }

    @Override
    public String toString() {
        return "TrafficRule{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", priority=" + priority +
                ", targetTag='" + targetTag + '\'' +
                ", conditionRelation=" + conditionRelation +
                ", conditions=" + conditions +
                ", percentage=" + percentage +
                ", hashKeySource=" + hashKeySource +
                ", hashKeyName='" + hashKeyName + '\'' +
                ", enabled=" + enabled +
                '}';
    }

    /**
     * 流量规则建造者
     */
    public static final class Builder {
        private String id;
        private String name;
        private int priority = 0;
        private String targetTag;
        private TrafficConditionRelation conditionRelation = TrafficConditionRelation.AND;
        private List<TrafficCondition> conditions = new ArrayList<>();
        private Integer percentage;
        private TrafficFieldSource hashKeySource = TrafficFieldSource.USER_ID;
        private String hashKeyName;
        private boolean enabled = true;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder targetTag(String targetTag) {
            this.targetTag = targetTag;
            return this;
        }

        public Builder conditionRelation(TrafficConditionRelation conditionRelation) {
            this.conditionRelation = conditionRelation;
            return this;
        }

        public Builder conditions(List<TrafficCondition> conditions) {
            this.conditions = conditions != null ? new ArrayList<>(conditions) : new ArrayList<>();
            return this;
        }

        public Builder addCondition(TrafficCondition condition) {
            if (this.conditions == null) {
                this.conditions = new ArrayList<>();
            }
            if (condition != null) {
                this.conditions.add(condition);
            }
            return this;
        }

        public Builder percentage(Integer percentage) {
            this.percentage = percentage;
            return this;
        }

        public Builder hashKeySource(TrafficFieldSource hashKeySource) {
            this.hashKeySource = hashKeySource;
            return this;
        }

        public Builder hashKeyName(String hashKeyName) {
            this.hashKeyName = hashKeyName;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public TrafficRule build() {
            TrafficRule rule = new TrafficRule();
            rule.setId(this.id);
            rule.setName(this.name);
            rule.setPriority(this.priority);
            rule.setTargetTag(this.targetTag);
            rule.setConditionRelation(this.conditionRelation);
            rule.setConditions(this.conditions);
            rule.setPercentage(this.percentage);
            rule.setHashKeySource(this.hashKeySource);
            rule.setHashKeyName(this.hashKeyName);
            rule.setEnabled(this.enabled);
            return rule;
        }
    }
}
