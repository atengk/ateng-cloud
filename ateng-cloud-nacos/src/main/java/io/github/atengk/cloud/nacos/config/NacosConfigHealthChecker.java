package io.github.atengk.cloud.nacos.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Nacos 现代配置中心启动健康自检与拓扑诊断器
 *
 * <p>在所有单例 Bean 实例化完成后执行自检，检查 Spring Boot 3 {@code spring.config.import}
 * 导入指令、远程 Nacos 服务端连接健康状态以及已生效的 Nacos 属性源拓扑，
 * 输出清晰友好的自检诊断日志，实现透明化运维。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class NacosConfigHealthChecker implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigHealthChecker.class);

    private final AtengNacosProperties properties;
    private final Environment environment;
    private final ObjectProvider<NacosConfigManager> configManagerProvider;

    public NacosConfigHealthChecker(AtengNacosProperties properties,
                                  Environment environment,
                                  ObjectProvider<NacosConfigManager> configManagerProvider) {
        this.properties = properties;
        this.environment = environment;
        this.configManagerProvider = configManagerProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (properties == null || !properties.isEnabled()
                || properties.getConfig() == null || !properties.getConfig().isEnabled()
                || !properties.getConfig().isCheckOnStartup()) {
            return;
        }

        checkAndLogStatus();
    }

    /**
     * 执行配置中心状态诊断并打印日志
     */
    public void checkAndLogStatus() {
        boolean hasImportDirective = detectConfigImportDirective();
        List<String> activeNacosSources = detectActiveNacosPropertySources();
        String serverStatus = detectServerStatus();
        String defaultGroup = properties.getConfig() != null
                ? properties.getConfig().getDefaultGroup() : "DEFAULT_GROUP";

        if (!activeNacosSources.isEmpty()) {
            log.info("[ateng-cloud-nacos] Nacos 现代配置中心已就绪 (ServerStatus: {}, DefaultGroup: {}). 激活配置源: {}",
                    serverStatus, defaultGroup, activeNacosSources);
        } else if (hasImportDirective) {
            log.info("[ateng-cloud-nacos] 未探测到远端 Nacos 配置源或处于离线模式 (ServerStatus: {})，已启用本地环境配置降级保底。",
                    serverStatus);
        } else {
            log.info("[ateng-cloud-nacos] 当前环境未检测到 spring.config.import=optional:nacos:... 配置导入指令，动态配置中心处于旁路待命状态。");
        }
    }

    /**
     * 探测当前环境是否声明了 nacos 配置导入指令
     */
    public boolean detectConfigImportDirective() {
        if (environment == null) {
            return false;
        }

        // 探测单一属性声明 spring.config.import
        String singleImport = environment.getProperty("spring.config.import");
        if (StringUtils.hasText(singleImport) && singleImport.contains("nacos:")) {
            return true;
        }

        // 探测列表格式声明 spring.config.import[0], [1], ...
        for (int i = 0; i < 20; i++) {
            String indexedImport = environment.getProperty("spring.config.import[" + i + "]");
            if (StringUtils.hasText(indexedImport) && indexedImport.contains("nacos:")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 探测当前 Environment 中已激活的 Nacos 属性源名称集合
     */
    public List<String> detectActiveNacosPropertySources() {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return Collections.emptyList();
        }

        List<String> nacosSources = new ArrayList<>();
        for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
            if (propertySource == null) {
                continue;
            }
            String name = propertySource.getName();
            if (name != null && (name.contains("nacos:")
                    || name.toLowerCase().contains("nacospropertysource")
                    || name.startsWith("NacosPropertySource"))) {
                nacosSources.add(name);
            }
        }
        return Collections.unmodifiableList(nacosSources);
    }

    /**
     * 探测 Nacos 配置中心服务端连接状态
     */
    public String detectServerStatus() {
        NacosConfigManager configManager = configManagerProvider != null
                ? configManagerProvider.getIfAvailable() : null;
        if (configManager == null) {
            return "UNAVAILABLE";
        }
        try {
            ConfigService configService = configManager.getConfigService();
            if (configService != null) {
                String status = configService.getServerStatus();
                return StringUtils.hasText(status) ? status : "UNKNOWN";
            }
        } catch (Exception ex) {
            log.debug("Failed to detect Nacos server status: {}", ex.getMessage());
        }
        return "DISCONNECTED";
    }
}
