package io.github.atengk.cloud.nacos.spi;

import io.github.atengk.cloud.nacos.event.SafeConfigRefreshEvent;

/**
 * Nacos 安全配置热更受管监听器 SPI
 *
 * <p>用于在配置热更新且 {@code @ConfigurationProperties} 增量重绑完成后，
 * 接收受管通知以安全调整本地缓存容量、连接池阈值等运行时状态。
 * 支持通过指定属性前缀进行精确过滤，避免无关配置变更触发无效回调。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public interface NacosConfigChangeListener {

    /**
     * 响应配置变更回调
     *
     * @param event 安全配置刷新事件（携带变更键值对与 MDC 上下文）
     */
    void onConfigChange(SafeConfigRefreshEvent event);

    /**
     * 关注的配置属性前缀或键
     * <p>默认返回空字符串，表示监听所有配置变更；
     * 若返回如 "ateng.sample"，则仅当变更集中包含此前缀开头的属性时才触发回调。</p>
     *
     * @return 关注的属性前缀
     */
    default String getPrefix() {
        return "";
    }

    /**
     * 监听器排序优先级，数值越小优先级越高
     *
     * @return 排序优先级，默认 0
     */
    default int getOrder() {
        return 0;
    }
}
