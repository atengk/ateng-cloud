package io.github.atengk.cloud.gateway.properties;

import java.time.Duration;

/**
 * 网关 Nacos 动态路由配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengGatewayDynamicRouteProperties {

    /**
     * 是否启用 Nacos 动态路由监听与快照热替换
     */
    private boolean enabled = true;

    /**
     * Nacos 配置 Data ID
     */
    private String dataId = "gateway-routes.json";

    /**
     * Nacos 配置分组 Group
     */
    private String group = "DEFAULT_GROUP";

    /**
     * Nacos 远程配置拉取与连接超时时间 (默认 5s)
     */
    private Duration timeout = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
