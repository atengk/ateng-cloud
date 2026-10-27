package io.github.atengk.cloud.nacos.metadata;

import java.util.Map;

/**
 * Nacos 实例元数据提供者门面接口
 *
 * @author Ateng
 * @since 2026-10-27
 */
public interface NacosMetadataProvider {

    /**
     * 获取当前节点全量收集并经过 SPI 定制后的元数据快照
     *
     * @return 不可变或防御性拷贝的元数据映射表，保证非空
     */
    Map<String, String> getMetadata();

    /**
     * 将当前收集到的元数据增量填充至目标注册表中
     *
     * @param targetMetadata 目标元数据映射表
     */
    void populateMetadata(Map<String, String> targetMetadata);
}
