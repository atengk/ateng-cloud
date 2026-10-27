package io.github.atengk.cloud.nacos.template;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.NacosConfigProperties;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingMaintainService;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.nacos.code.NacosResultCode;
import io.github.atengk.cloud.nacos.filter.ZoneAffinityInstanceFilter;
import io.github.atengk.cloud.nacos.lifecycle.NacosLifecycleStateMachine;
import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NacosTemplate} 单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class NacosTemplateTest {

    @Test
    @DisplayName("离线平滑空转：当未配置 Nacos 或发现禁用时，查询接口返回安全默认值")
    void testSmoothIdleRotationWhenDiscoveryDisabled() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        AtengNacosProperties properties = new AtengNacosProperties();
        NacosMetadataProvider metadataProvider = mock(NacosMetadataProvider.class);

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                properties,
                metadataProvider,
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        assertThat(template.getAllInstances("any-service")).isEmpty();
        assertThat(template.getHealthyInstances("any-service")).isEmpty();
        assertThat(template.getInstances("any-service")).isEmpty();
        assertThat(template.selectOneHealthyInstance("any-service")).isEmpty();
        assertThat(template.getMyInstance()).isEmpty();
        assertThat(template.getSameZoneInstances("any-service")).isEmpty();
        assertThat(template.getServices(1, 10)).isEmpty();
        assertThat(template.isUnderProtection("any-service")).isFalse();

        // 写入操作安全空转，不抛出异常
        template.markOnline();
        template.markOffline();
        template.setInstanceEnabled("any-service", "127.0.0.1", 8080, true);
        template.updateInstanceWeight("any-service", "127.0.0.1", 8080, 1.0);
        template.registerMetadata("test-key", "test-val");
        template.removeMetadata("test-key");
        template.updateInstanceMetadata("any-service", "127.0.0.1", 8080, Map.of("k", "v"));
    }

    @Test
    @DisplayName("实例查询与动态权重：严格过滤 weight <= 0.0 与禁用节点")
    void testGetAllAndHealthyInstancesWithWeightFiltering() throws Exception {
        NacosServiceManager serviceManager = mock(NacosServiceManager.class);
        NamingService namingService = mock(NamingService.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setServerAddr("127.0.0.1:8848");

        when(serviceManager.getNamingService(any())).thenReturn(namingService);

        Instance validInst = createInstance("192.168.1.10", 8080, "zone-a", 1.0, true, true);
        Instance zeroWeightInst = createInstance("192.168.1.11", 8080, "zone-a", 0.0, true, true);
        Instance disabledInst = createInstance("192.168.1.12", 8080, "zone-a", 1.0, true, false);

        when(namingService.getAllInstances(eq("demo-service"), anyString()))
                .thenReturn(List.of(validInst, zeroWeightInst, disabledInst));
        when(namingService.selectInstances(eq("demo-service"), anyString(), eq(true)))
                .thenReturn(new ArrayList<>(List.of(validInst, zeroWeightInst, disabledInst)));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("serviceManager", serviceManager);
        beanFactory.addBean("discoveryProperties", discoveryProperties);

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                new AtengNacosProperties(),
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        List<Instance> all = template.getAllInstances("demo-service");
        assertThat(all).hasSize(3);

        List<Instance> healthy = template.getHealthyInstances("demo-service");
        assertThat(healthy).hasSize(1);
        assertThat(healthy.get(0).getIp()).isEqualTo("192.168.1.10");

        Optional<Instance> selected = template.selectOneHealthyInstance("demo-service");
        assertThat(selected).isPresent();
        assertThat(selected.get().getIp()).isEqualTo("192.168.1.10");
    }

    @Test
    @DisplayName("机房亲和路由：按调用方所在机房优先筛选健康实例")
    void testSameZoneInstances() throws Exception {
        NacosServiceManager serviceManager = mock(NacosServiceManager.class);
        NamingService namingService = mock(NamingService.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setServerAddr("127.0.0.1:8848");

        when(serviceManager.getNamingService(any())).thenReturn(namingService);

        Instance instZoneA = createInstance("192.168.1.10", 8080, "zone-a", 1.0, true, true);
        Instance instZoneB = createInstance("192.168.2.10", 8080, "zone-b", 1.0, true, true);

        when(namingService.selectInstances(eq("demo-service"), anyString(), eq(true)))
                .thenReturn(new ArrayList<>(List.of(instZoneA, instZoneB)));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("serviceManager", serviceManager);
        beanFactory.addBean("discoveryProperties", discoveryProperties);

        AtengNacosProperties properties = new AtengNacosProperties();
        properties.setZone("zone-a");

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                properties,
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        List<Instance> sameZone = template.getSameZoneInstances("demo-service");
        assertThat(sameZone).hasSize(1);
        assertThat(sameZone.get(0).getIp()).isEqualTo("192.168.1.10");
    }

    @Test
    @DisplayName("异常因果转译：底层 NacosException 100% 转换为 BusinessException 且保留原始 cause")
    void testExceptionCausalTranslation() throws Exception {
        NacosServiceManager serviceManager = mock(NacosServiceManager.class);
        NamingService namingService = mock(NamingService.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setServerAddr("127.0.0.1:8848");

        when(serviceManager.getNamingService(any())).thenReturn(namingService);

        NacosException originalNacosEx = new NacosException(500, "Connection refused to Nacos cluster");
        when(namingService.getAllInstances(eq("failing-service"), anyString()))
                .thenThrow(originalNacosEx);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("serviceManager", serviceManager);
        beanFactory.addBean("discoveryProperties", discoveryProperties);

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                new AtengNacosProperties(),
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        assertThatThrownBy(() -> template.getAllInstances("failing-service"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(NacosResultCode.NACOS_CLIENT_ERROR.getCode());
                    assertThat(be.getCause()).isSameAs(originalNacosEx);
                });
    }

    @Test
    @DisplayName("对称上下线操作：联动 NacosLifecycleStateMachine")
    void testMarkOnlineAndOffline() {
        NacosLifecycleStateMachine stateMachine = mock(NacosLifecycleStateMachine.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("stateMachine", stateMachine);

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                new AtengNacosProperties(),
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        template.markOnline();
        verify(stateMachine).markOnline();

        template.markOffline();
        verify(stateMachine).markOffline();
    }

    @Test
    @DisplayName("实例状态与权重维护：调用 NamingMaintainService.updateInstance")
    void testSetInstanceEnabledAndUpdateWeight() throws Exception {
        NacosServiceManager serviceManager = mock(NacosServiceManager.class);
        NamingMaintainService maintainService = mock(NamingMaintainService.class);
        NamingService namingService = mock(NamingService.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setServerAddr("127.0.0.1:8848");

        when(serviceManager.getNamingMaintainService(any())).thenReturn(maintainService);
        when(serviceManager.getNamingService(any())).thenReturn(namingService);
        when(namingService.getAllInstances(anyString(), anyString())).thenReturn(Collections.emptyList());

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("serviceManager", serviceManager);
        beanFactory.addBean("discoveryProperties", discoveryProperties);

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                new AtengNacosProperties(),
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        template.setInstanceEnabled("order-service", "192.168.1.100", 8080, false);
        template.updateInstanceWeight("order-service", "192.168.1.100", 8080, 0.0);

        verify(maintainService, org.mockito.Mockito.times(2)).updateInstance(eq("order-service"), anyString(), any(Instance.class));
    }

    @Test
    @DisplayName("本节点实例状态、权重与元数据动态维护：调用 NamingService.registerInstance (gRPC)")
    void testCurrentInstanceOperationsViaGrpc() throws Exception {
        NacosServiceManager serviceManager = mock(NacosServiceManager.class);
        NamingService namingService = mock(NamingService.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setServerAddr("127.0.0.1:8848");
        discoveryProperties.setService("order-service");
        discoveryProperties.setIp("192.168.1.100");
        discoveryProperties.setPort(8080);

        when(serviceManager.getNamingService(any())).thenReturn(namingService);
        when(namingService.getAllInstances(anyString(), anyString())).thenReturn(Collections.emptyList());

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("serviceManager", serviceManager);
        beanFactory.addBean("discoveryProperties", discoveryProperties);

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                new AtengNacosProperties(),
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        template.registerMetadata("new-key", "new-val");
        template.setInstanceEnabled("order-service", "192.168.1.100", 8080, false);
        template.updateInstanceWeight("order-service", "192.168.1.100", 8080, 0.5);
        template.removeMetadata("new-key");

        verify(namingService, org.mockito.Mockito.times(4)).registerInstance(eq("order-service"), anyString(), any(Instance.class));
    }

    @Test
    @DisplayName("配置中心离线平滑降级：在未装配 ConfigManager 或离线时安全返回默认值与空 Optional")
    void testConfigOperationsOfflineFallback() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        AtengNacosProperties properties = new AtengNacosProperties();

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                beanFactory.getBeanProvider(NacosConfigManager.class),
                properties,
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        assertThat(template.isConfigAvailable()).isFalse();
        assertThat(template.getConfigServerStatus()).isEqualTo("UNAVAILABLE");
        assertThat(template.getConfig("order-service.yml")).isNull();
        assertThat(template.findConfig("order-service.yml")).isEmpty();
        assertThat(template.publishConfig("order-service.yml", "test: true")).isFalse();
        assertThat(template.publishConfigCas("order-service.yml", "DEFAULT_GROUP", "test: true", "md5")).isFalse();
        assertThat(template.removeConfig("order-service.yml")).isFalse();

        // 监听器注册安全空转不报错
        Listener listener = mock(Listener.class);
        template.addConfigListener("order-service.yml", listener);
        template.removeConfigListener("order-service.yml", listener);
    }

    @Test
    @DisplayName("配置中心对称操作：正常读写、CAS 发布与监听器生命周期管理")
    void testConfigOperationsSuccess() throws Exception {
        NacosConfigManager configManager = mock(NacosConfigManager.class);
        ConfigService configService = mock(ConfigService.class);
        NacosConfigProperties configProperties = new NacosConfigProperties();
        configProperties.setGroup("DEFAULT_GROUP");
        configProperties.setTimeout(3000);

        when(configManager.getConfigService()).thenReturn(configService);
        when(configManager.getNacosConfigProperties()).thenReturn(configProperties);
        when(configService.getServerStatus()).thenReturn("UP");
        when(configService.getConfig(eq("app.yml"), eq("DEFAULT_GROUP"), any(Long.class)))
                .thenReturn("server:\n  port: 8080");
        when(configService.publishConfig(eq("app.yml"), eq("DEFAULT_GROUP"), anyString())).thenReturn(true);
        when(configService.publishConfig(eq("app.yml"), eq("DEFAULT_GROUP"), anyString(), eq("yaml"))).thenReturn(true);
        when(configService.publishConfigCas(eq("app.yml"), eq("DEFAULT_GROUP"), anyString(), eq("md5-123"), eq("yaml")))
                .thenReturn(true);
        when(configService.removeConfig(eq("app.yml"), eq("DEFAULT_GROUP"))).thenReturn(true);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("configManager", configManager);

        AtengNacosProperties properties = new AtengNacosProperties();
        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                beanFactory.getBeanProvider(NacosConfigManager.class),
                properties,
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        assertThat(template.isConfigAvailable()).isTrue();
        assertThat(template.getConfigServerStatus()).isEqualTo("UP");

        // 查询配置
        String config = template.getConfig("app.yml");
        assertThat(config).isEqualTo("server:\n  port: 8080");
        Optional<String> optionalConfig = template.findConfig("app.yml", "DEFAULT_GROUP", Duration.ofSeconds(2));
        assertThat(optionalConfig).isPresent().contains("server:\n  port: 8080");

        // 发布配置与 CAS 发布
        boolean published = template.publishConfig("app.yml", "server:\n  port: 8081");
        assertThat(published).isTrue();
        boolean publishedYaml = template.publishConfig("app.yml", "DEFAULT_GROUP", "server:\n  port: 8081", "yaml");
        assertThat(publishedYaml).isTrue();
        boolean publishedCas = template.publishConfigCas("app.yml", "DEFAULT_GROUP", "server:\n  port: 8082", "md5-123", "yaml");
        assertThat(publishedCas).isTrue();

        // 监听器注册与注销
        Listener listener = mock(Listener.class);
        template.addConfigListener("app.yml", listener);
        verify(configService).addListener(eq("app.yml"), eq("DEFAULT_GROUP"), eq(listener));

        template.removeConfigListener("app.yml", listener);
        verify(configService).removeListener(eq("app.yml"), eq("DEFAULT_GROUP"), eq(listener));

        // 删除配置
        boolean removed = template.removeConfig("app.yml");
        assertThat(removed).isTrue();
        verify(configService).removeConfig(eq("app.yml"), eq("DEFAULT_GROUP"));
    }

    @Test
    @DisplayName("配置中心异常转译：捕获底层 NacosException 并包装为 BusinessException 保持因果链")
    void testConfigOperationsExceptionTranslation() throws Exception {
        NacosConfigManager configManager = mock(NacosConfigManager.class);
        ConfigService configService = mock(ConfigService.class);
        when(configManager.getConfigService()).thenReturn(configService);
        when(configService.getConfig(anyString(), anyString(), any(Long.class)))
                .thenThrow(new NacosException(500, "Connection timeout to Nacos server"));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("configManager", configManager);

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                beanFactory.getBeanProvider(NacosConfigManager.class),
                new AtengNacosProperties(),
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        assertThatThrownBy(() -> template.getConfig("broken.yml"))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(NacosException.class)
                .hasMessageContaining("Connection timeout to Nacos server");
    }

    @Test
    @DisplayName("配置中心边界防卫：空入参校验 Fail-Fast 拦截")
    void testConfigOperationsInputValidation() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                beanFactory.getBeanProvider(NacosConfigManager.class),
                new AtengNacosProperties(),
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        assertThatThrownBy(() -> template.getConfig(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.getConfig("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.publishConfig("", "val")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.removeConfig("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.addConfigListener("valid.yml", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.removeConfigListener("valid.yml", null)).isInstanceOf(IllegalArgumentException.class);
    }

    private Instance createInstance(String ip, int port, String zone, double weight, boolean healthy, boolean enabled) {
        Instance inst = new Instance();
        inst.setIp(ip);
        inst.setPort(port);
        inst.setWeight(weight);
        inst.setHealthy(healthy);
        inst.setEnabled(enabled);
        Map<String, String> meta = new HashMap<>();
        if (zone != null) {
            meta.put("zone", zone);
        }
        inst.setMetadata(meta);
        return inst;
    }
}
