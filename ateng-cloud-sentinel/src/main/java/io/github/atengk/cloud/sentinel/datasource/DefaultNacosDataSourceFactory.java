package io.github.atengk.cloud.sentinel.datasource;

import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;

import java.util.Properties;

/**
 * 默认 Sentinel Nacos 数据源构造工厂
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class DefaultNacosDataSourceFactory implements NacosDataSourceFactory {

    @Override
    public <T> ReadableDataSource<String, T> createDataSource(
            Properties properties, String groupId, String dataId, Converter<String, T> converter) {
        return new NacosDataSource<>(properties, groupId, dataId, converter);
    }
}
