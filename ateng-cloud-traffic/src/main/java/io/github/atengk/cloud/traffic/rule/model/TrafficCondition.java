package io.github.atengk.cloud.traffic.rule.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 流量规则匹配条件模型
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficCondition implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 匹配字段来源 (Header, Query, Cookie, Path, User, Tenant, IP)
     */
    private TrafficFieldSource fieldSource = TrafficFieldSource.HEADER;

    /**
     * 字段名称 (如 Header 名、Query 参数名、Cookie 名；Path/User/Tenant/IP 可缺省)
     */
    private String fieldName;

    /**
     * 谓词匹配操作符 (默认精确匹配 EXACT)
     */
    private TrafficMatchOperator operator = TrafficMatchOperator.EXACT;

    /**
     * 期望值 (用于 EXACT, PREFIX, REGEX 或逗号分隔的 IN)
     */
    private String expectedValue;

    /**
     * 多期望值列表 (主要用于 IN 包含匹配)
     */
    private List<String> expectedValues = new ArrayList<>();

    /**
     * 是否区分大小写 (默认区分大小写 true)
     */
    private boolean caseSensitive = true;

    public TrafficCondition() {
    }

    public TrafficCondition(TrafficFieldSource fieldSource, String fieldName, TrafficMatchOperator operator, String expectedValue) {
        this.fieldSource = fieldSource;
        this.fieldName = fieldName;
        this.operator = operator;
        this.expectedValue = expectedValue;
    }

    public static Builder builder() {
        return new Builder();
    }

    public TrafficFieldSource getFieldSource() {
        return fieldSource;
    }

    public void setFieldSource(TrafficFieldSource fieldSource) {
        this.fieldSource = fieldSource;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public TrafficMatchOperator getOperator() {
        return operator;
    }

    public void setOperator(TrafficMatchOperator operator) {
        this.operator = operator;
    }

    public String getExpectedValue() {
        return expectedValue;
    }

    public void setExpectedValue(String expectedValue) {
        this.expectedValue = expectedValue;
    }

    public List<String> getExpectedValues() {
        return expectedValues != null ? Collections.unmodifiableList(expectedValues) : Collections.emptyList();
    }

    public void setExpectedValues(List<String> expectedValues) {
        this.expectedValues = expectedValues != null ? new ArrayList<>(expectedValues) : new ArrayList<>();
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TrafficCondition that)) {
            return false;
        }
        return caseSensitive == that.caseSensitive
                && fieldSource == that.fieldSource
                && Objects.equals(fieldName, that.fieldName)
                && operator == that.operator
                && Objects.equals(expectedValue, that.expectedValue)
                && Objects.equals(expectedValues, that.expectedValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldSource, fieldName, operator, expectedValue, expectedValues, caseSensitive);
    }

    @Override
    public String toString() {
        return "TrafficCondition{" +
                "fieldSource=" + fieldSource +
                ", fieldName='" + fieldName + '\'' +
                ", operator=" + operator +
                ", expectedValue='" + expectedValue + '\'' +
                ", expectedValues=" + expectedValues +
                ", caseSensitive=" + caseSensitive +
                '}';
    }

    /**
     * 流量匹配条件建造者
     */
    public static final class Builder {
        private TrafficFieldSource fieldSource = TrafficFieldSource.HEADER;
        private String fieldName;
        private TrafficMatchOperator operator = TrafficMatchOperator.EXACT;
        private String expectedValue;
        private List<String> expectedValues = new ArrayList<>();
        private boolean caseSensitive = true;

        private Builder() {
        }

        public Builder fieldSource(TrafficFieldSource fieldSource) {
            this.fieldSource = fieldSource;
            return this;
        }

        public Builder fieldName(String fieldName) {
            this.fieldName = fieldName;
            return this;
        }

        public Builder operator(TrafficMatchOperator operator) {
            this.operator = operator;
            return this;
        }

        public Builder expectedValue(String expectedValue) {
            this.expectedValue = expectedValue;
            return this;
        }

        public Builder expectedValues(List<String> expectedValues) {
            this.expectedValues = expectedValues != null ? new ArrayList<>(expectedValues) : new ArrayList<>();
            return this;
        }

        public Builder addExpectedValue(String value) {
            if (this.expectedValues == null) {
                this.expectedValues = new ArrayList<>();
            }
            this.expectedValues.add(value);
            return this;
        }

        public Builder caseSensitive(boolean caseSensitive) {
            this.caseSensitive = caseSensitive;
            return this;
        }

        public TrafficCondition build() {
            TrafficCondition condition = new TrafficCondition();
            condition.setFieldSource(this.fieldSource);
            condition.setFieldName(this.fieldName);
            condition.setOperator(this.operator);
            condition.setExpectedValue(this.expectedValue);
            condition.setExpectedValues(this.expectedValues);
            condition.setCaseSensitive(this.caseSensitive);
            return condition;
        }
    }
}
