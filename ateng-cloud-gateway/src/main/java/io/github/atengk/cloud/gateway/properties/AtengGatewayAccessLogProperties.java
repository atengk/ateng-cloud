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

    /**
     * 是否在访问日志中包含请求 Query 查询参数 (默认 false)
     */
    private boolean includeQuery = false;

    /**
     * 自定义敏感 Query 参数名脱敏列表 (匹配到的键其参数值将被替换为 ***，不区分大小写)
     */
    private List<String> maskKeys = new ArrayList<>();

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

    public boolean isIncludeQuery() {
        return includeQuery;
    }

    public void setIncludeQuery(boolean includeQuery) {
        this.includeQuery = includeQuery;
    }

    public List<String> getMaskKeys() {
        return maskKeys;
    }

    public void setMaskKeys(List<String> maskKeys) {
        this.maskKeys = maskKeys;
    }
}
