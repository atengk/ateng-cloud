package io.github.atengk.cloud.sample.nacos.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;

/**
 * 配置热更失败状态与告警透出视图对象
 *
 * <p>封装配置动态热更失败领域事件的监听捕获统计与最新失败上下文，
 * 用于展示配置中心安全加固与异常告警感知状态。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class RefreshFailureStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 捕获的失败事件总数
     */
    private final int failureCount;

    /**
     * 最近一次失败的配置集唯一标识
     */
    private final String lastDataId;

    /**
     * 最近一次失败的配置分组
     */
    private final String lastGroup;

    /**
     * 最近一次失败的受影响属性键集合
     */
    private final Set<String> lastChangedKeys;

    /**
     * 最近一次失败的错误描述信息
     */
    private final String lastErrorMessage;

    /**
     * 最近一次失败的链路追踪标识
     */
    private final String lastTraceId;

    public RefreshFailureStatusVO(int failureCount,
                                  String lastDataId,
                                  String lastGroup,
                                  Set<String> lastChangedKeys,
                                  String lastErrorMessage,
                                  String lastTraceId) {
        this.failureCount = failureCount;
        this.lastDataId = lastDataId != null ? lastDataId : "";
        this.lastGroup = lastGroup != null ? lastGroup : "";
        this.lastChangedKeys = lastChangedKeys != null ? lastChangedKeys : Collections.emptySet();
        this.lastErrorMessage = lastErrorMessage != null ? lastErrorMessage : "";
        this.lastTraceId = lastTraceId != null ? lastTraceId : "";
    }

    public int getFailureCount() {
        return failureCount;
    }

    public String getLastDataId() {
        return lastDataId;
    }

    public String getLastGroup() {
        return lastGroup;
    }

    public Set<String> getLastChangedKeys() {
        return lastChangedKeys;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public String getLastTraceId() {
        return lastTraceId;
    }
}
