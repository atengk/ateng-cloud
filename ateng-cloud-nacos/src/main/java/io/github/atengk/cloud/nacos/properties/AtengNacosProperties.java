package io.github.atengk.cloud.nacos.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nacos 微服务治理配置属性
 *
 * @author Ateng
 * @since 2026-10-27
 */
@ConfigurationProperties(prefix = "ateng.cloud.nacos")
public class AtengNacosProperties {

    /**
     * 是否启用 ateng-cloud Nacos 治理增强
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
