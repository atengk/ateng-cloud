package io.github.atengk.cloud.feign.properties;

import java.util.ArrayList;
import java.util.List;

/**
 * Feign 双模自适应契约解包配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignAdaptiveUnwrapProperties {

    /**
     * 是否全局开启自适应契约解包，默认 true
     */
    private boolean enabled = true;

    /**
     * 排除的 Feign 客户端名称列表（白名单豁免解包，适用于第三方或非标接口）
     */
    private List<String> excludeClients = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getExcludeClients() {
        return excludeClients;
    }

    public void setExcludeClients(List<String> excludeClients) {
        this.excludeClients = excludeClients;
    }
}
