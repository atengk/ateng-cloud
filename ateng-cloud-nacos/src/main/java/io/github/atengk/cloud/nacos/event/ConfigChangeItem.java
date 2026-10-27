package io.github.atengk.cloud.nacos.event;

import io.github.atengk.cloud.nacos.mask.ConfigMasker;

import java.io.Serializable;
import java.util.Objects;

/**
 * 单个配置属性变更明细项
 *
 * <p>封装属性键、变更前旧值、变更后新值及变更类型。
 * 遵循“内存真明文”不变量：内存字段始终保留原始值，仅在 {@link #toString()}、日志及 {@link #getMaskedOldValue()}、
 * {@link #getMaskedNewValue()} 边界对敏感属性实施防御性星号掩码脱敏。</p>
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
     * 变更前旧值（新增时为 null，内存保留原始明文）
     */
    private final Object oldValue;

    /**
     * 变更后新值（删除时为 null，内存保留原始明文）
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

    /**
     * 判断当前配置项键名是否命中敏感特征（如 password, secret, token, key, credential 等）
     */
    public boolean isSensitive() {
        return ConfigMasker.isSensitive(this.key);
    }

    /**
     * 对指定属性值执行脱敏
     */
    private Object maskValue(Object value) {
        if (value instanceof String str) {
            return ConfigMasker.mask(key, str);
        }
        return value != null && isSensitive() ? "******" : value;
    }

    /**
     * 获取脱敏后的旧值（若为敏感属性且非空则返回星号掩码 {@code ****** }，否则返回原始值）
     */
    public Object getMaskedOldValue() {
        return maskValue(oldValue);
    }

    /**
     * 获取脱敏后的新值（若为敏感属性且非空则返回星号掩码 {@code ****** }，否则返回原始值）
     */
    public Object getMaskedNewValue() {
        return maskValue(newValue);
    }

    /**
     * 转换为值已脱敏的明细项副本
     */
    public ConfigChangeItem toMaskedItem() {
        return new ConfigChangeItem(key, getMaskedOldValue(), getMaskedNewValue(), changeType);
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
                ", oldValue=" + getMaskedOldValue() +
                ", newValue=" + getMaskedNewValue() +
                ", changeType=" + changeType +
                '}';
    }
}
