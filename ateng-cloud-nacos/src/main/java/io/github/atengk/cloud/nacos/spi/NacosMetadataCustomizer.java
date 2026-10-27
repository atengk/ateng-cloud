package io.github.atengk.cloud.nacos.spi;

import org.springframework.core.Ordered;

import java.util.Map;

/**
 * Nacos 实例元数据定制扩展点 SPI
 *
 * <p>允许下游微服务或中间件在服务节点向 Nacos 发起注册前，增量改写或注入自定义业务标签。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
@FunctionalInterface
public interface NacosMetadataCustomizer extends Ordered {

    /**
     * 定制或扩充当前实例即将上报的元数据字典
     *
     * @param metadata 当前已收集的元数据键值对字典（可读写）
     */
    void customize(Map<String, String> metadata);

    @Override
    default int getOrder() {
        return 0;
    }
}
