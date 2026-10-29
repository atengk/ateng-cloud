package io.github.atengk.cloud.seata.datasource;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.ds.ItemDataSource;
import com.baomidou.dynamic.datasource.enums.SeataMode;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import io.seata.rm.datasource.DataSourceProxy;
import io.seata.rm.datasource.xa.DataSourceProxyXA;
import org.apache.seata.common.holder.ObjectHolder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 动态数据源物理连接池 Seata 代理自检与协同协调器离线单测
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("SeataDynamicDataSourceCoordinator 协同协调器单测")
class SeataDynamicDataSourceCoordinatorTest {

    @BeforeAll
    static void initSeataEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("seata.config.type", "file");
        env.setProperty("seata.config.file.name", "file.conf");
        env.setProperty("seata.tx-service-group", "default_tx_group");
        env.setProperty("seata.service.vgroup-mapping.default_tx_group", "default");
        env.setProperty("seata.service.grouplist.default", "127.0.0.1:8091");
        ObjectHolder.INSTANCE.setObject("springConfigurableEnvironment", env);
    }

    private DataSource createMockDataSource() throws SQLException {
        DataSource ds = mock(DataSource.class, withSettings().extraInterfaces(XADataSource.class));
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        XAConnection xaConn = mock(XAConnection.class);

        when(ds.getConnection()).thenReturn(conn);
        when(((XADataSource) ds).getXAConnection()).thenReturn(xaConn);
        when(xaConn.getConnection()).thenReturn(conn);

        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getURL()).thenReturn("jdbc:mysql://localhost:3306/test_db");
        when(meta.getUserName()).thenReturn("root");
        when(meta.getDatabaseProductName()).thenReturn("MySQL");
        when(meta.getDatabaseProductVersion()).thenReturn("8.0.32");

        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(anyString())).thenReturn("8.0.32");
        when(rs.getString(1)).thenReturn("8.0.32");
        return ds;
    }


    @Test
    @DisplayName("自动为未代理的物理数据源进行 Seata AT 代理包装")
    void shouldWrapUnproxiedDataSourceWithAtMode() throws SQLException {
        SeataProperties seataProperties = new SeataProperties();
        SeataDynamicDataSourceCoordinator coordinator = new SeataDynamicDataSourceCoordinator(seataProperties);

        DynamicRoutingDataSource routingDs = new DynamicRoutingDataSource(Collections.emptyList());
        DataSource rawDataSource = createMockDataSource();
        routingDs.addDataSource("order_ds", rawDataSource);

        assertThat(coordinator.isSeataProxied(rawDataSource)).isFalse();

        coordinator.ensureDataSourcesProxied("dynamicDataSource", routingDs);

        DataSource proxiedDs = routingDs.getDataSource("order_ds");
        assertThat(coordinator.isSeataProxied(proxiedDs)).isTrue();
        assertThat(proxiedDs).isInstanceOf(ItemDataSource.class);
        ItemDataSource itemDs = (ItemDataSource) proxiedDs;
        assertThat(itemDs.getSeata()).isTrue();
        assertThat(itemDs.getSeataMode()).isEqualTo(SeataMode.AT);
        assertThat(itemDs.getDataSource()).isInstanceOf(DataSourceProxy.class);
    }

    @Test
    @DisplayName("配置为 XA 模式时为未代理物理数据源进行 Seata XA 代理包装")
    void shouldWrapUnproxiedDataSourceWithXaMode() throws SQLException {
        SeataProperties seataProperties = new SeataProperties();
        seataProperties.getDynamicDatasource().setMode("XA");
        SeataDynamicDataSourceCoordinator coordinator = new SeataDynamicDataSourceCoordinator(seataProperties);

        DynamicRoutingDataSource routingDs = new DynamicRoutingDataSource(Collections.emptyList());
        DataSource rawDataSource = createMockDataSource();
        routingDs.addDataSource("pay_ds", rawDataSource);

        coordinator.ensureDataSourcesProxied("dynamicDataSource", routingDs);

        DataSource proxiedDs = routingDs.getDataSource("pay_ds");
        assertThat(coordinator.isSeataProxied(proxiedDs)).isTrue();
        assertThat(proxiedDs).isInstanceOf(ItemDataSource.class);
        ItemDataSource itemDs = (ItemDataSource) proxiedDs;
        assertThat(itemDs.getSeata()).isTrue();
        assertThat(itemDs.getSeataMode()).isEqualTo(SeataMode.XA);
        assertThat(itemDs.getDataSource()).isInstanceOf(DataSourceProxyXA.class);
    }

    @Test
    @DisplayName("已完成 Seata 代理的数据源不会被重复包装")
    void shouldNotDoubleWrapAlreadyProxiedDataSource() throws SQLException {
        SeataProperties seataProperties = new SeataProperties();
        SeataDynamicDataSourceCoordinator coordinator = new SeataDynamicDataSourceCoordinator(seataProperties);

        DataSource rawDataSource = createMockDataSource();
        DataSourceProxy proxy = new DataSourceProxy(rawDataSource);
        ItemDataSource alreadyProxied = new ItemDataSource("user_ds", rawDataSource, proxy, false, true, SeataMode.AT);

        DynamicRoutingDataSource routingDs = new DynamicRoutingDataSource(Collections.emptyList());
        routingDs.addDataSource("user_ds", alreadyProxied);

        coordinator.ensureDataSourcesProxied("dynamicDataSource", routingDs);

        DataSource resultDs = routingDs.getDataSource("user_ds");
        assertThat(resultDs).isSameAs(alreadyProxied);
    }

    @Test
    @DisplayName("容器启动单例就绪回调后自动处理容器中所有 DynamicRoutingDataSource")
    void shouldHandleContextBeansOnAfterSingletonsInstantiated() throws SQLException {
        SeataProperties seataProperties = new SeataProperties();
        SeataDynamicDataSourceCoordinator coordinator = new SeataDynamicDataSourceCoordinator(seataProperties);

        GenericApplicationContext context = new GenericApplicationContext();
        DynamicRoutingDataSource routingDs = new DynamicRoutingDataSource(Collections.emptyList());
        DataSource rawDataSource = createMockDataSource();
        routingDs.addDataSource("master", rawDataSource);

        context.registerBean("dynamicDataSource", DynamicRoutingDataSource.class, () -> routingDs);
        context.refresh();

        coordinator.setApplicationContext(context);
        coordinator.afterSingletonsInstantiated();

        DataSource currentDs = routingDs.getDataSource("master");
        assertThat(coordinator.isSeataProxied(currentDs)).isTrue();
    }
}
