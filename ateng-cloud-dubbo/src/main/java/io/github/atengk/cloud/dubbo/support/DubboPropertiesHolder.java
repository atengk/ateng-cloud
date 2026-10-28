package io.github.atengk.cloud.dubbo.support;

import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;

/**
 * Dubbo 配置属性上下文持有者 (供 SPI 过滤器等组件在无 Spring 直接注入时安全读取)
 *
 * @author Ateng
 * @since 2026-10-28
 */
public final class DubboPropertiesHolder {

    private static volatile AtengDubboProperties properties = new AtengDubboProperties();

    private DubboPropertiesHolder() {
    }

    /**
     * 获取当前生效的 Dubbo 配置属性
     *
     * @return Dubbo 配置属性
     */
    public static AtengDubboProperties get() {
        return properties;
    }

    /**
     * 设置生效的 Dubbo 配置属性
     *
     * @param props Dubbo 配置属性
     */
    public static void set(AtengDubboProperties props) {
        if (props != null) {
            properties = props;
        }
    }

    /**
     * 重置配置属性为默认值
     */
    public static void reset() {
        properties = new AtengDubboProperties();
    }
}
