package io.github.atengk.cloud.nacos.event;

import org.springframework.context.ApplicationEvent;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Nacos 安全配置热更强类型事件
 *
 * <p>在配置热更新且 {@code @ConfigurationProperties} 增量重绑完成后由受管热更引擎发布，
 * 携带属性键变更集、旧值新值对比明细及 MDC 链路追踪上下文，支持通过 Spring {@code @EventListener} 响应。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class SafeConfigRefreshEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * 触发本次热更的 Nacos 配置集唯一标识（可为空）
     */
    private final String dataId;

    /**
     * 触发本次热更的 Nacos 配置分组名（可为空）
     */
    private final String group;

    /**
     * 本次发生变更的配置属性键名集合
     */
    private final Set<String> changedKeys;

    /**
     * 配置属性详细变更明细（键 -> 变更明细项）
     */
    private final Map<String, ConfigChangeItem> changeDetails;

    /**
     * MDC 链路追踪标识 (traceId)
     */
    private final String traceId;

    public SafeConfigRefreshEvent(Object source,
                                 String dataId,
                                 String group,
                                 Set<String> changedKeys,
                                 Map<String, ConfigChangeItem> changeDetails,
                                 String traceId) {
        super(source);
        this.dataId = dataId;
        this.group = group;
        this.changedKeys = changedKeys != null ? Collections.unmodifiableSet(changedKeys) : Collections.emptySet();
        this.changeDetails = changeDetails != null ? Collections.unmodifiableMap(changeDetails) : Collections.emptyMap();
        this.traceId = traceId != null ? traceId : "";
    }

    public String getDataId() {
        return dataId;
    }

    public String getGroup() {
        return group;
    }

    public Set<String> getChangedKeys() {
        return changedKeys;
    }

    public Map<String, ConfigChangeItem> getChangeDetails() {
        return changeDetails;
    }

    public String getTraceId() {
        return traceId;
    }

    /**
     * 获取指定配置键的变更明细
     *
     * @param key 配置属性键名
     * @return 变更明细项，未变更时返回 null
     */
    public ConfigChangeItem getChange(String key) {
        return changeDetails.get(key);
    }

    /**
     * 判断变更集中是否包含指定配置键
     *
     * @param key 配置属性键名
     * @return 是否包含
     */
    public boolean containsKey(String key) {
        return changedKeys.contains(key);
    }

    /**
     * 判断变更集中是否存在匹配指定前缀的配置项
     *
     * @param prefix 属性前缀
     * @return 若有任何属性键匹配此前缀则返回 true
     */
    public boolean containsPrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return !changedKeys.isEmpty();
        }
        for (String key : changedKeys) {
            if (key != null && (key.startsWith(prefix) || key.equals(prefix))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "SafeConfigRefreshEvent{" +
                "dataId='" + dataId + '\'' +
                ", group='" + group + '\'' +
                ", traceId='" + traceId + '\'' +
                ", changedKeysCount=" + changedKeys.size() +
                ", changeDetails=" + changeDetails +
                '}';
    }
}
