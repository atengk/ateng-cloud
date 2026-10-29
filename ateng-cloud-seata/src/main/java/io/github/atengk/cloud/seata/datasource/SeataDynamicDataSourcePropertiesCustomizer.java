package io.github.atengk.cloud.seata.datasource;

import com.baomidou.dynamic.datasource.enums.SeataMode;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceProperties;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourcePropertiesCustomizer;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Baomidou dynamic-datasource 属性协同定制器
 * <p>
 * 协同 dynamic-datasource 原生属性配置，当微服务开启 ateng-cloud-seata 时，
 * 自动激活 dynamic-datasource 的原生 Seata 代理机制（{@code seata=true}），
 * 并根据配置同步事务代理模式（AT 或 XA），确保各物理连接池在创建时被正确代理包装。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SeataDynamicDataSourcePropertiesCustomizer implements DynamicDataSourcePropertiesCustomizer {

    private static final Logger log = LoggerFactory.getLogger(SeataDynamicDataSourcePropertiesCustomizer.class);

    private final SeataProperties seataProperties;

    public SeataDynamicDataSourcePropertiesCustomizer(SeataProperties seataProperties) {
        this.seataProperties = seataProperties != null ? seataProperties : new SeataProperties();
    }

    @Override
    public void customize(DynamicDataSourceProperties properties) {
        if (!seataProperties.isEnabled() || !seataProperties.getDynamicDatasource().isEnabled()) {
            return;
        }

        if (seataProperties.getDynamicDatasource().isAutoProxy()) {
            if (!Boolean.TRUE.equals(properties.getSeata())) {
                properties.setSeata(true);
                log.info("ateng-cloud-seata 自动协同开启 Baomidou dynamic-datasource 原生 Seata 代理机制 (seata=true)");
            }
            if ("XA".equalsIgnoreCase(seataProperties.getDynamicDatasource().getMode())) {
                properties.setSeataMode(SeataMode.XA);
                log.info("配置 dynamic-datasource Seata 事务代理模式为: XA");
            } else {
                properties.setSeataMode(SeataMode.AT);
                log.debug("配置 dynamic-datasource Seata 事务代理模式为: AT");
            }
        }
    }
}
