package io.github.atengk.cloud.feign.properties;

import java.util.ArrayList;
import java.util.List;

/**
 * Feign 客户端启动期饥饿预热配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignPreheatProperties {

    /**
     * 是否开启启动期饥饿预热机制，默认 true
     */
    private boolean enabled = true;

    /**
     * 需要显式预热的 Feign 客户端名称列表（为空时将自动预热应用上下文中扫描到的全部 FeignClient 代理）
     */
    private List<String> clients = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getClients() {
        return clients;
    }

    public void setClients(List<String> clients) {
        this.clients = clients;
    }
}
