package io.github.atengk.cloud.seata.datasource;

import com.baomidou.dynamic.datasource.enums.SeataMode;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceProperties;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Baomidou dynamic-datasource 属性协同定制器离线单测
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("SeataDynamicDataSourcePropertiesCustomizer 属性定制器单测")
class SeataDynamicDataSourcePropertiesCustomizerTest {

    @Test
    @DisplayName("默认配置下自动开启 dynamic-datasource 原生 seata 代理并配置 AT 模式")
    void shouldEnableSeataAndAtModeByDefault() {
        SeataProperties seataProperties = new SeataProperties();
        SeataDynamicDataSourcePropertiesCustomizer customizer = new SeataDynamicDataSourcePropertiesCustomizer(seataProperties);

        DynamicDataSourceProperties properties = new DynamicDataSourceProperties();
        assertThat(properties.getSeata()).isFalse();

        customizer.customize(properties);

        assertThat(properties.getSeata()).isTrue();
        assertThat(properties.getSeataMode()).isEqualTo(SeataMode.AT);
    }

    @Test
    @DisplayName("配置为 XA 模式时同步设置为 SeataMode.XA")
    void shouldSetXaModeWhenConfigured() {
        SeataProperties seataProperties = new SeataProperties();
        seataProperties.getDynamicDatasource().setMode("XA");
        SeataDynamicDataSourcePropertiesCustomizer customizer = new SeataDynamicDataSourcePropertiesCustomizer(seataProperties);

        DynamicDataSourceProperties properties = new DynamicDataSourceProperties();
        customizer.customize(properties);

        assertThat(properties.getSeata()).isTrue();
        assertThat(properties.getSeataMode()).isEqualTo(SeataMode.XA);
    }

    @Test
    @DisplayName("当 ateng-cloud-seata 全局禁用时跳过属性定制")
    void shouldSkipWhenSeataDisabled() {
        SeataProperties seataProperties = new SeataProperties();
        seataProperties.setEnabled(false);
        SeataDynamicDataSourcePropertiesCustomizer customizer = new SeataDynamicDataSourcePropertiesCustomizer(seataProperties);

        DynamicDataSourceProperties properties = new DynamicDataSourceProperties();
        customizer.customize(properties);

        assertThat(properties.getSeata()).isFalse();
    }

    @Test
    @DisplayName("当 dynamic-datasource 协同禁用时跳过属性定制")
    void shouldSkipWhenDynamicDatasourceDisabled() {
        SeataProperties seataProperties = new SeataProperties();
        seataProperties.getDynamicDatasource().setEnabled(false);
        SeataDynamicDataSourcePropertiesCustomizer customizer = new SeataDynamicDataSourcePropertiesCustomizer(seataProperties);

        DynamicDataSourceProperties properties = new DynamicDataSourceProperties();
        customizer.customize(properties);

        assertThat(properties.getSeata()).isFalse();
    }

    @Test
    @DisplayName("当 autoProxy 设置为 false 时跳过代理属性开启")
    void shouldSkipWhenAutoProxyDisabled() {
        SeataProperties seataProperties = new SeataProperties();
        seataProperties.getDynamicDatasource().setAutoProxy(false);
        SeataDynamicDataSourcePropertiesCustomizer customizer = new SeataDynamicDataSourcePropertiesCustomizer(seataProperties);

        DynamicDataSourceProperties properties = new DynamicDataSourceProperties();
        customizer.customize(properties);

        assertThat(properties.getSeata()).isFalse();
    }
}
