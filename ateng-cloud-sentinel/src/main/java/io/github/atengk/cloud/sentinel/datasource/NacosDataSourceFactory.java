package io.github.atengk.cloud.sentinel.datasource;

import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.ReadableDataSource;

import java.util.Properties;

/**
 * Sentinel Nacos 数据源构造工厂 (扩展点 SPI)
 *
 * @author Ateng
 * @since 2026-10-29
 */
@FunctionalInterface
public interface NacosDataSourceFactory {

    /**
     * 创建 Sentinel 可读数据源
     *
     * @param properties Nacos 客户端连接配置
     * @param groupId    配置分组 ID
     * @param dataId     配置数据集 ID
     * @param converter  规则数据转换器
     * @param <T>        规则实体类型
     * @return 可读数据源实例
     */
    <T> ReadableDataSource<String, T> createDataSource(
            Properties properties, String groupId, String dataId, Converter<String, T> converter);
}
