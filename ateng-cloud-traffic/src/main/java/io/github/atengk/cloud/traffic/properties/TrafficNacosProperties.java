package io.github.atengk.cloud.traffic.properties;

import java.io.Serializable;

/**
 * Nacos 动态流量分流规则分级监听配置属性
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficNacosProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否启用 Nacos 动态规则分级监听与原子热更
     */
    private boolean enabled = true;

    /**
     * 规则所在 Nacos 配置分组 (默认 DEFAULT_GROUP)
     */
    private String group = "DEFAULT_GROUP";

    /**
     * 全局通用分流规则 Data ID (默认 traffic-rules.json)
     */
    private String globalDataId = "traffic-rules.json";

    /**
     * 应用专属分流规则 Data ID (缺省时自动解析为 ${spring.application.name}-traffic-rules.json)
     */
    private String appDataId;

    /**
     * 是否优先加载应用专属规则 (true 时若专属规则存在则优先应用，缺省时平滑回退至全局规则)
     */
    private boolean preferAppRule = true;

    /**
     * 规则合并策略: OVERWRITE (应用规则覆盖全局规则), MERGE (应用规则与全局规则合并按优先级排序)
     */
    private String mergeStrategy = "OVERWRITE";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getGlobalDataId() {
        return globalDataId;
    }

    public void setGlobalDataId(String globalDataId) {
        this.globalDataId = globalDataId;
    }

    public String getAppDataId() {
        return appDataId;
    }

    public void setAppDataId(String appDataId) {
        this.appDataId = appDataId;
    }

    public boolean isPreferAppRule() {
        return preferAppRule;
    }

    public void setPreferAppRule(boolean preferAppRule) {
        this.preferAppRule = preferAppRule;
    }

    public String getMergeStrategy() {
        return mergeStrategy;
    }

    public void setMergeStrategy(String mergeStrategy) {
        this.mergeStrategy = mergeStrategy;
    }
}
