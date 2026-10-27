package io.github.atengk.cloud.nacos.event;

import java.io.Serializable;
import java.util.Objects;

/**
 * 单个配置属性变更明细项
 *
 * <p>封装属性键、变更前旧值、变更后新值及变更类型。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class ConfigChangeItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 配置属性键名
     */
    private final String key;

    /**
     * 变更前旧值（新增时为 null）
     */
    private final Object oldValue;

    /**
     * 变更后新值（删除时为 null）
     */
    private final Object newValue;

    /**
     * 变更类型
     */
    private final ConfigChangeType changeType;

    public ConfigChangeItem(String key, Object oldValue, Object newValue, ConfigChangeType changeType) {
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changeType = changeType != null ? changeType : ConfigChangeType.MODIFIED;
    }

    public String getKey() {
        return key;
    }

    public Object getOldValue() {
        return oldValue;
    }

    public Object getNewValue() {
        return newValue;
    }

    public ConfigChangeType getChangeType() {
        return changeType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfigChangeItem that = (ConfigChangeItem) o;
        return Objects.equals(key, that.key) &&
                Objects.equals(oldValue, that.oldValue) &&
                Objects.equals(newValue, that.newValue) &&
                changeType == that.changeType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, oldValue, newValue, changeType);
    }

    @Override
    public String toString() {
        return "ConfigChangeItem{" +
                "key='" + key + '\'' +
                ", oldValue=" + oldValue +
                ", newValue=" + newValue +
                ", changeType=" + changeType +
                '}';
    }
}
