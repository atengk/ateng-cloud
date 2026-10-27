package io.github.atengk.cloud.nacos.event;

/**
 * Nacos 配置属性变更类型枚举
 *
 * @author Ateng
 * @since 2026-10-27
 */
public enum ConfigChangeType {

    /**
     * 新增属性配置项
     */
    ADDED,

    /**
     * 修改既有属性配置项
     */
    MODIFIED,

    /**
     * 删除属性配置项
     */
    DELETED
}
