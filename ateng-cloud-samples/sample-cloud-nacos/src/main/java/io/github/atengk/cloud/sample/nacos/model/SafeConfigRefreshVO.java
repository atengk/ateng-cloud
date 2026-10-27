package io.github.atengk.cloud.sample.nacos.model;

import java.io.Serializable;
import java.util.Set;

/**
 * 安全热更结果响应视图对象
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class SafeConfigRefreshVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String traceId;
    private Set<String> changedKeys;
    private int changedKeysCount;
    private boolean success;

    public SafeConfigRefreshVO() {
    }

    public SafeConfigRefreshVO(String traceId, Set<String> changedKeys, int changedKeysCount, boolean success) {
        this.traceId = traceId;
        this.changedKeys = changedKeys;
        this.changedKeysCount = changedKeysCount;
        this.success = success;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Set<String> getChangedKeys() {
        return changedKeys;
    }

    public void setChangedKeys(Set<String> changedKeys) {
        this.changedKeys = changedKeys;
    }

    public int getChangedKeysCount() {
        return changedKeysCount;
    }

    public void setChangedKeysCount(int changedKeysCount) {
        this.changedKeysCount = changedKeysCount;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }
}
