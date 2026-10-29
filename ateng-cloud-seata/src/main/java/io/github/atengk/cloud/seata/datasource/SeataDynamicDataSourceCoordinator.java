package io.github.atengk.cloud.seata.datasource;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.ds.ItemDataSource;
import com.baomidou.dynamic.datasource.enums.SeataMode;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import io.seata.rm.datasource.DataSourceProxy;
import io.seata.rm.datasource.xa.DataSourceProxyXA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Baomidou 动态数据源物理连接池 Seata 代理自检与协同协调器
 * <p>
 * 在所有单例 Bean 实例化完成后，遍历 DynamicRoutingDataSource 中已注册的各物理数据源，
 * 验证并确保所有物理连接池均已完成 Seata DataSourceProxy（或 DataSourceProxyXA）代理包装。
 * 若探测到未经代理包装的物理连接池，执行防御性就地包装，确保全局事务管辖无死角。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SeataDynamicDataSourceCoordinator implements SmartInitializingSingleton, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(SeataDynamicDataSourceCoordinator.class);

    private final SeataProperties seataProperties;
    private ApplicationContext applicationContext;

    public SeataDynamicDataSourceCoordinator(SeataProperties seataProperties) {
        this.seataProperties = seataProperties != null ? seataProperties : new SeataProperties();
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!seataProperties.isEnabled() || !seataProperties.getDynamicDatasource().isEnabled()
                || !seataProperties.getDynamicDatasource().isAutoProxy()) {
            return;
        }

        if (applicationContext == null) {
            return;
        }

        Map<String, DynamicRoutingDataSource> routingDataSources = applicationContext.getBeansOfType(DynamicRoutingDataSource.class);
        if (routingDataSources.isEmpty()) {
            log.debug("未探测到 DynamicRoutingDataSource 动态路由数据源组件，跳过物理连接池 Seata 代理自检");
            return;
        }

        for (Map.Entry<String, DynamicRoutingDataSource> entry : routingDataSources.entrySet()) {
            String beanName = entry.getKey();
            DynamicRoutingDataSource routingDs = entry.getValue();
            ensureDataSourcesProxied(beanName, routingDs);
        }
    }

    /**
     * 校验并确保动态数据源中所有物理连接池均完成 Seata 代理包装
     *
     * @param beanName  动态路由数据源 Bean 名称
     * @param routingDs 动态路由数据源实例
     */
    public void ensureDataSourcesProxied(String beanName, DynamicRoutingDataSource routingDs) {
        if (routingDs == null) {
            return;
        }

        Map<String, DataSource> dataSources = routingDs.getDataSources();
        if (dataSources == null || dataSources.isEmpty()) {
            return;
        }

        boolean isXa = "XA".equalsIgnoreCase(seataProperties.getDynamicDatasource().getMode());
        for (Map.Entry<String, DataSource> dsEntry : dataSources.entrySet()) {
            String dsKey = dsEntry.getKey();
            DataSource ds = dsEntry.getValue();

            if (!isSeataProxied(ds)) {
                log.info("动态数据源 [{}] 下物理数据源 [{}] 未完成 Seata 代理，执行就地防御性包装", beanName, dsKey);
                DataSource proxiedDs = wrapDataSource(dsKey, ds, isXa);
                if (proxiedDs != ds) {
                    routingDs.addDataSource(dsKey, proxiedDs);
                }
            }
        }
    }

    /**
     * 判断指定数据源是否已被 Seata DataSourceProxy 或 DataSourceProxyXA 代理包装
     *
     * @param dataSource 待检测数据源
     * @return true 表示已被代理；false 表示未经代理
     */
    public boolean isSeataProxied(DataSource dataSource) {
        if (dataSource == null) {
            return false;
        }
        if (dataSource instanceof DataSourceProxy || dataSource instanceof DataSourceProxyXA) {
            return true;
        }
        if (dataSource instanceof ItemDataSource itemDs) {
            if (Boolean.TRUE.equals(itemDs.getSeata())) {
                return true;
            }
            DataSource innerDs = itemDs.getDataSource();
            return innerDs instanceof DataSourceProxy || innerDs instanceof DataSourceProxyXA;
        }
        return false;
    }

    /**
     * 将原始数据源包装为 Seata 代理数据源及 ItemDataSource
     *
     * @param name       数据源标识
     * @param dataSource 原始物理数据源
     * @param isXa       是否使用 XA 模式代理
     * @return 包装后的数据源实例
     */
    public DataSource wrapDataSource(String name, DataSource dataSource, boolean isXa) {
        DataSource realDs = dataSource;
        if (dataSource instanceof ItemDataSource itemDs) {
            realDs = itemDs.getRealDataSource();
        }

        try {
            DataSource targetProxy;
            if (isXa) {
                targetProxy = new DataSourceProxyXA(realDs);
                return new ItemDataSource(name, realDs, targetProxy, false, true, SeataMode.XA);
            } else {
                targetProxy = new DataSourceProxy(realDs);
                return new ItemDataSource(name, realDs, targetProxy, false, true, SeataMode.AT);
            }
        } catch (Throwable t) {
            log.warn("对物理数据源 [{}] 包装 Seata 代理时发生异常，降级保留原始数据源: {}", name, t.getMessage());
            return dataSource;
        }
    }
}
