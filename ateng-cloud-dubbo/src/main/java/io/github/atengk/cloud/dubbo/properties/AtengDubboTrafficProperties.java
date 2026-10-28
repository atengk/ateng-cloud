package io.github.atengk.cloud.dubbo.properties;

/**
 * Dubbo 金丝雀流量染色与标签路由配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengDubboTrafficProperties {

    /**
     * 是否开启金丝雀流量染色与标签路由，默认 true
     */
    private boolean enabled = true;

    /**
     * 是否强制要求命中标签实例 (true: 无灰度实例时直接报错; false: 无灰度实例时平滑降级到基线实例)，微服务生产防雪崩基线默认 false
     */
    private boolean tagForce = false;

    /**
     * 流量染色请求头/MDC键名，默认 X-Traffic-Tag
     */
    private String headerName = "X-Traffic-Tag";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTagForce() {
        return tagForce;
    }

    public void setTagForce(boolean tagForce) {
        this.tagForce = tagForce;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }
}
