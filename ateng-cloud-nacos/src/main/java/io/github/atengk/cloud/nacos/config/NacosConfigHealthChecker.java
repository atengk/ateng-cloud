package io.github.atengk.cloud.nacos.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import io.github.atengk.cloud.nacos.health.NacosHealthChecker;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

/**
 * Nacos 现代配置中心启动健康自检与拓扑诊断器
 *
 * @author Ateng
 * @since 2026-10-27
 * @deprecated 请迁移使用 {@link NacosHealthChecker} 全景健康就绪诊断器
 */
@Deprecated(since = "1.0.0", forRemoval = false)
public class NacosConfigHealthChecker extends NacosHealthChecker {

    public NacosConfigHealthChecker(AtengNacosProperties properties,
                                  Environment environment,
                                  ObjectProvider<NacosConfigManager> configManagerProvider) {
        super(properties, environment, configManagerProvider, null, null);
    }
}
