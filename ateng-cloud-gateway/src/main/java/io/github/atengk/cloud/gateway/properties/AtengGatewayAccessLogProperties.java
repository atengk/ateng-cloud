package io.github.atengk.cloud.gateway.properties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 网关轻量响应式访问日志与审计配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengGatewayAccessLogProperties {

    /**
     * 是否启用轻量响应式访问日志
     */
    private boolean enabled = false;

    /**
     * 慢请求报警阈值 (超过该阈值时记录 WARN 级别的慢接口日志，默认 3s)
     */
    private Duration slowThreshold = Duration.ofSeconds(3);

    /**
     * 访问日志忽略记录的 AntPath 路径列表 (如 /actuator/**, /favicon.ico)
     */
    private List<String> excludePaths = new ArrayList<>(List.of("/actuator/**", "/favicon.ico"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getSlowThreshold() {
        return slowThreshold;
    }

    public void setSlowThreshold(Duration slowThreshold) {
        this.slowThreshold = slowThreshold;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }
}
