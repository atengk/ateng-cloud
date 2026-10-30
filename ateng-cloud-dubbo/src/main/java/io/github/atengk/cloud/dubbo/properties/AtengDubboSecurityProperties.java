package io.github.atengk.cloud.dubbo.properties;

import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Dubbo 生产级安全防护配置属性
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class AtengDubboSecurityProperties {

    /**
     * 序列化安全校验配置
     */
    @NestedConfigurationProperty
    private AtengDubboSerializeProperties serialize = new AtengDubboSerializeProperties();

    public AtengDubboSerializeProperties getSerialize() {
        return serialize;
    }

    public void setSerialize(AtengDubboSerializeProperties serialize) {
        this.serialize = serialize;
    }
}
