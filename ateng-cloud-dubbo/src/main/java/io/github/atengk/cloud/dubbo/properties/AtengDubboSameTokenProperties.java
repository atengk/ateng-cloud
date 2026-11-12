package io.github.atengk.cloud.dubbo.properties;

import java.util.ArrayList;
import java.util.List;

/**
 * Dubbo 零信任 Same-Token 内部防穿透配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengDubboSameTokenProperties {

    /**
     * 是否开启 Sa-Token 同源受信凭据透传与校验，默认 true
     */
    private boolean enabled = true;

    /**
     * Provider 端是否强制校验 Same-Token (未携带直接 401 快速失败)，默认 true
     */
    private boolean check = true;

    /**
     * 豁免 Same-Token 入站强校验的 RPC 接口全限定名或通配符列表
     */
    private List<String> excludeInterfaces = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isCheck() {
        return check;
    }

    public void setCheck(boolean check) {
        this.check = check;
    }

    public List<String> getExcludeInterfaces() {
        return excludeInterfaces;
    }

    public void setExcludeInterfaces(List<String> excludeInterfaces) {
        if (excludeInterfaces == null || excludeInterfaces.isEmpty()) {
            this.excludeInterfaces = List.of();
            return;
        }
        List<String> normalized = new ArrayList<>(excludeInterfaces.size());
        for (String pattern : excludeInterfaces) {
            if (pattern != null && !pattern.isBlank()) {
                normalized.add(pattern.trim());
            }
        }
        this.excludeInterfaces = normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }
}
