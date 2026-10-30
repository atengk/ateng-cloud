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
import io.github.atengk.cloud.nacos.config.NacosConfigParser;
import io.github.atengk.cloud.nacos.constant.ConfigFormat;
import io.github.atengk.cloud.nacos.filter.ZoneAffinityInstanceFilter;
import io.github.atengk.cloud.nacos.lifecycle.NacosLifecycleStateMachine;
import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NacosTemplate} 单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class NacosTemplateTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

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
    void testConfigOperationsOfflineFallback() throws Exception {
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
        assertThat(template.getConfig("order-service.yml", AppTestConfig.class)).isNull();
        assertThat(template.findConfig("order-service.yml")).isEmpty();
        assertThat(template.findConfig("order-service.yml", AppTestConfig.class)).isEmpty();
        assertThat(template.publishConfig("order-service.yml", "test: true")).isFalse();
        assertThat(template.publishConfigCas("order-service.yml", "DEFAULT_GROUP", "test: true", "md5")).isFalse();
        assertThat(template.removeConfig("order-service.yml")).isFalse();

        // 编程式订阅离线平滑返回
        AutoCloseable sub = template.subscribeConfig("order-service.yml", AppTestConfig.class, cfg -> {});
        assertThat(sub).isNotNull();
        sub.close();

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
    @DisplayName("配置中心异常转译：捕获底层 NacosException 与常规异常，保持因果链并精准映射错误码")
    void testConfigOperationsExceptionTranslation() throws Exception {
        NacosConfigManager configManager = mock(NacosConfigManager.class);
        ConfigService configService = mock(ConfigService.class);
        when(configManager.getConfigService()).thenReturn(configService);
        when(configService.getConfig(anyString(), anyString(), any(Long.class)))
                .thenThrow(new NacosException(500, "Connection timeout to Nacos server"));
        doThrow(new NacosException(500, "Failed to add listener"))
                .when(configService).addListener(eq("broken.yml"), anyString(), any(Listener.class));
        doThrow(new IllegalStateException("Failed to remove listener"))
                .when(configService).removeListener(eq("broken.yml"), anyString(), any(Listener.class));
        when(configService.removeConfig(eq("broken.yml"), anyString()))
                .thenThrow(new IllegalStateException("Unexpected config error"));

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

        // NacosException -> NACOS_CLIENT_ERROR
        assertThatThrownBy(() -> template.getConfig("broken.yml"))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(NacosException.class)
                .hasMessageContaining("Connection timeout to Nacos server")
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(NacosResultCode.NACOS_CLIENT_ERROR.getCode()));

        // addConfigListener NacosException -> NACOS_CLIENT_ERROR
        Listener listener = mock(Listener.class);
        assertThatThrownBy(() -> template.addConfigListener("broken.yml", listener))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(NacosException.class)
                .hasMessageContaining("Failed to add listener")
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(NacosResultCode.NACOS_CLIENT_ERROR.getCode()));

        // removeConfigListener 异常因果转译 -> NACOS_CONFIG_OPERATION_FAILED (对称防线，彻底杜绝静默吞咽)
        assertThatThrownBy(() -> template.removeConfigListener("broken.yml", listener))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to remove listener")
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(NacosResultCode.NACOS_CONFIG_OPERATION_FAILED.getCode()));

        // 非 NacosException -> NACOS_CONFIG_OPERATION_FAILED
        assertThatThrownBy(() -> template.removeConfig("broken.yml"))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected config error")
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(NacosResultCode.NACOS_CONFIG_OPERATION_FAILED.getCode()));
    }

    @Test
    @DisplayName("服务与配置中心强可用性断言校验：未启用或离线时抛出精准专属错误码")
    void testEnsureAvailabilityChecks() {
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

        assertThatThrownBy(template::ensureConfigAvailable)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(NacosResultCode.NACOS_CONFIG_DISABLED.getCode()));

        assertThatThrownBy(template::ensureDiscoveryAvailable)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(NacosResultCode.NACOS_DISCOVERY_DISABLED.getCode()));
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

    public static class AppTestConfig {
        private String name;
        private int port;

        public AppTestConfig() {
        }

        public AppTestConfig(String name, int port) {
            this.name = name;
            this.port = port;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    @Test
    @DisplayName("配置中心泛型读取：根据 Data ID 后缀推断格式并反序列化为 POJO")
    void testTypedConfigReadAndFind() throws Exception {
        NacosConfigManager configManager = mock(NacosConfigManager.class);
        ConfigService configService = mock(ConfigService.class);
        NacosConfigProperties configProperties = new NacosConfigProperties();
        configProperties.setGroup("DEFAULT_GROUP");
        configProperties.setTimeout(3000);

        when(configManager.getConfigService()).thenReturn(configService);
        when(configManager.getNacosConfigProperties()).thenReturn(configProperties);
        when(configService.getServerStatus()).thenReturn("UP");
        when(configService.getConfig(eq("order-service.yaml"), eq("DEFAULT_GROUP"), any(Long.class)))
                .thenReturn("name: order-service\nport: 8080");
        when(configService.getConfig(eq("payment-service.json"), eq("DEFAULT_GROUP"), any(Long.class)))
                .thenReturn("{\"name\": \"payment-service\", \"port\": 8081}");
        when(configService.getConfig(eq("missing.yaml"), eq("DEFAULT_GROUP"), any(Long.class)))
                .thenReturn(null);

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

        // YAML 读取
        AppTestConfig yamlConfig = template.getConfig("order-service.yaml", AppTestConfig.class);
        assertThat(yamlConfig).isNotNull();
        assertThat(yamlConfig.getName()).isEqualTo("order-service");
        assertThat(yamlConfig.getPort()).isEqualTo(8080);

        Optional<AppTestConfig> yamlOpt = template.findConfig("order-service.yaml", AppTestConfig.class);
        assertThat(yamlOpt).isPresent();
        assertThat(yamlOpt.get().getName()).isEqualTo("order-service");

        // JSON 读取
        AppTestConfig jsonConfig = template.getConfig("payment-service.json", AppTestConfig.class);
        assertThat(jsonConfig).isNotNull();
        assertThat(jsonConfig.getName()).isEqualTo("payment-service");
        assertThat(jsonConfig.getPort()).isEqualTo(8081);

        // 不存在的配置
        assertThat(template.getConfig("missing.yaml", AppTestConfig.class)).isNull();
        assertThat(template.findConfig("missing.yaml", AppTestConfig.class)).isEmpty();
    }

    @Test
    @DisplayName("配置发布与语法预检：校验非法 YAML/JSON 抛出转译业务异常，正常对象序列化发布")
    void testValidateAndPublishFailFast() throws Exception {
        NacosConfigManager configManager = mock(NacosConfigManager.class);
        ConfigService configService = mock(ConfigService.class);
        NacosConfigProperties configProperties = new NacosConfigProperties();
        configProperties.setGroup("DEFAULT_GROUP");
        configProperties.setTimeout(3000);

        when(configManager.getConfigService()).thenReturn(configService);
        when(configManager.getNacosConfigProperties()).thenReturn(configProperties);
        when(configService.getServerStatus()).thenReturn("UP");
        when(configService.publishConfig(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(configService.publishConfig(anyString(), anyString(), anyString())).thenReturn(true);

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

        // 1. 独立语法校验
        template.validateConfig("key: value", ConfigFormat.YAML);
        assertThatThrownBy(() -> template.validateConfig("bad: [unclosed", ConfigFormat.YAML))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(NacosResultCode.NACOS_CONFIG_VALIDATION_FAILED.getCode()));

        // 2. 发布非法 YAML 文本触发 Fail-Fast 阻断，不调用底层 ConfigService
        assertThatThrownBy(() -> template.publishConfig("app.yml", "bad: [unclosed"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(NacosResultCode.NACOS_CONFIG_VALIDATION_FAILED.getCode()));

        // 3. 发布合法对象
        AppTestConfig configObj = new AppTestConfig("demo-app", 9090);
        boolean published = template.publishConfig("app.yml", configObj);
        assertThat(published).isTrue();

        verify(configService).publishConfig(eq("app.yml"), eq("DEFAULT_GROUP"), anyString(), eq("yaml"));
    }

    @Test
    @DisplayName("编程式动态订阅：首次立即派发、MDC traceId 穿透、自闭合注销与容器销毁兜底")
    void testSubscribeConfigLifecycleAndMdc() throws Exception {
        NacosConfigManager configManager = mock(NacosConfigManager.class);
        ConfigService configService = mock(ConfigService.class);
        NacosConfigProperties configProperties = new NacosConfigProperties();
        configProperties.setGroup("DEFAULT_GROUP");
        configProperties.setTimeout(3000);

        when(configManager.getConfigService()).thenReturn(configService);
        when(configManager.getNacosConfigProperties()).thenReturn(configProperties);
        when(configService.getServerStatus()).thenReturn("UP");
        when(configService.getConfig(eq("dynamic.yml"), eq("DEFAULT_GROUP"), any(Long.class)))
                .thenReturn("name: initial\nport: 8000");

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

        AtomicReference<AppTestConfig> deliveredConfig = new AtomicReference<>();
        AtomicReference<String> deliveredTraceId = new AtomicReference<>();

        // 1. 订阅配置并断言首次立即派发与 MDC 穿透
        AutoCloseable sub = template.subscribeConfig("dynamic.yml", AppTestConfig.class, cfg -> {
            deliveredConfig.set(cfg);
            deliveredTraceId.set(MDC.get("traceId"));
        });

        assertThat(deliveredConfig.get()).isNotNull();
        assertThat(deliveredConfig.get().getName()).isEqualTo("initial");
        assertThat(deliveredConfig.get().getPort()).isEqualTo(8000);
        assertThat(deliveredTraceId.get()).isNotBlank();
        // 主线程 MDC 上下文无污染
        assertThat(MDC.get("traceId")).isNull();
        assertThat(template.getActiveSubscriptionCount()).isEqualTo(1);

        // 2. 模拟 Nacos 推送配置更新
        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(configService).addListener(eq("dynamic.yml"), eq("DEFAULT_GROUP"), listenerCaptor.capture());
        Listener registeredListener = listenerCaptor.getValue();
        assertThat(registeredListener).isNotNull();

        registeredListener.receiveConfigInfo("name: updated\nport: 8001");
        assertThat(deliveredConfig.get().getName()).isEqualTo("updated");
        assertThat(deliveredConfig.get().getPort()).isEqualTo(8001);
        assertThat(MDC.get("traceId")).isNull();

        // 3. 句柄显式注销
        sub.close();
        verify(configService).removeListener(eq("dynamic.yml"), eq("DEFAULT_GROUP"), eq(registeredListener));
        assertThat(template.getActiveSubscriptionCount()).isEqualTo(0);

        // 4. 测试容器 destroy 兜底注销
        AutoCloseable sub2 = template.subscribeConfig("dynamic.yml", AppTestConfig.class, cfg -> {});
        assertThat(template.getActiveSubscriptionCount()).isEqualTo(1);
        template.destroy();
        assertThat(template.getActiveSubscriptionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("编程式动态订阅：消费回调异常物理隔离，不破坏监听链路")
    void testSubscribeConfigExceptionBarrier() throws Exception {
        NacosConfigManager configManager = mock(NacosConfigManager.class);
        ConfigService configService = mock(ConfigService.class);
        NacosConfigProperties configProperties = new NacosConfigProperties();
        configProperties.setGroup("DEFAULT_GROUP");
        configProperties.setTimeout(3000);

        when(configManager.getConfigService()).thenReturn(configService);
        when(configManager.getNacosConfigProperties()).thenReturn(configProperties);
        when(configService.getServerStatus()).thenReturn("UP");
        when(configService.getConfig(anyString(), anyString(), any(Long.class))).thenReturn(null);

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

        AutoCloseable sub = template.subscribeConfig("barrier.yml", AppTestConfig.class, cfg -> {
            throw new RuntimeException("Simulated business callback crash");
        });

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(configService).addListener(eq("barrier.yml"), eq("DEFAULT_GROUP"), listenerCaptor.capture());

        // 抛出异常被隔离屏障捕获，不向外逃逸
        listenerCaptor.getValue().receiveConfigInfo("name: crash\nport: 9999");
        assertThat(MDC.get("traceId")).isNull();

        sub.close();
    }

    @Test
    @DisplayName("CAS 发布语法预检：遇到语法错误直接 Fail-Fast 抛出 BusinessException 阻断推送")
    void testPublishConfigCasSyntaxValidation() throws Exception {
        NacosConfigManager configManager = mock(NacosConfigManager.class);
        ConfigService configService = mock(ConfigService.class);
        when(configManager.getConfigService()).thenReturn(configService);
        when(configService.getServerStatus()).thenReturn("UP");

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

        // 格式错误（非法 YAML）：直接被语法预检拦截阻断
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                template.publishConfigCas("rule.yaml", "DEFAULT_GROUP", "invalid: yaml: [", "md5-1", "yaml")
        ).isInstanceOf(io.github.atengk.boot.core.exception.BusinessException.class);

        // 绝不穿透调用底层 configService
        verify(configService, never()).publishConfigCas(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("编程式订阅空安全：远端配置被清空或解析为 null 时跳过回调，杜绝 NPE")
    void testSubscribeConfigNullOrEmptyContentSafety() throws Exception {
        NacosConfigManager configManager = mock(NacosConfigManager.class);
        ConfigService configService = mock(ConfigService.class);
        NacosConfigProperties configProperties = new NacosConfigProperties();
        configProperties.setGroup("DEFAULT_GROUP");
        when(configManager.getConfigService()).thenReturn(configService);
        when(configManager.getNacosConfigProperties()).thenReturn(configProperties);
        when(configService.getServerStatus()).thenReturn("UP");
        when(configService.getConfig(anyString(), anyString(), any(Long.class))).thenReturn(null);

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

        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        AutoCloseable sub = template.subscribeConfig("empty-safe.yml", AppTestConfig.class, cfg -> {
            callCount.incrementAndGet();
        });

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(configService).addListener(eq("empty-safe.yml"), eq("DEFAULT_GROUP"), listenerCaptor.capture());
        Listener listener = listenerCaptor.getValue();

        // 1. 推送 null 配置内容
        listener.receiveConfigInfo(null);
        assertThat(callCount.get()).isEqualTo(0);

        // 2. 推送纯空白配置内容
        listener.receiveConfigInfo("   ");
        assertThat(callCount.get()).isEqualTo(0);

        // 3. 推送有效配置内容，成功触发回调
        listener.receiveConfigInfo("name: valid\nport: 8080");
        assertThat(callCount.get()).isEqualTo(1);

        sub.close();
    }

    @Test
    @DisplayName("注册发现服务端连接探测：正常 UP 与异常 DISCONNECTED 对称探查")
    void testNamingServerStatus() throws Exception {
        com.alibaba.cloud.nacos.NacosDiscoveryProperties discoveryProperties =
                mock(com.alibaba.cloud.nacos.NacosDiscoveryProperties.class);
        com.alibaba.nacos.api.naming.NamingService namingService =
                mock(com.alibaba.nacos.api.naming.NamingService.class);
        when(discoveryProperties.namingServiceInstance()).thenReturn(namingService);
        when(namingService.getServerStatus()).thenReturn("UP");

        NacosServiceManager serviceManager = mock(NacosServiceManager.class);
        when(serviceManager.getNamingService(any())).thenReturn(namingService);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("discoveryProperties", discoveryProperties);
        beanFactory.addBean("serviceManager", serviceManager);

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                beanFactory.getBeanProvider(NacosConfigManager.class),
                new AtengNacosProperties(),
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        assertThat(template.getNamingServerStatus()).isEqualTo("UP");

        // 异常捕获转译
        when(namingService.getServerStatus()).thenThrow(new RuntimeException("Connection refused"));
        assertThat(template.getNamingServerStatus()).isEqualTo("DISCONNECTED");
    }

    @Test
    @DisplayName("服务实例编程式动态订阅：离线自洽保底回调空集合且返回 No-Op 句柄")
    void testSubscribeInstancesOfflineSmoothIdle() throws Exception {
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

        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicReference<List<Instance>> receivedRef = new java.util.concurrent.atomic.AtomicReference<>();

        AutoCloseable sub = template.subscribeInstances("order-service", list -> {
            callCount.incrementAndGet();
            receivedRef.set(list);
        });

        assertThat(sub).isNotNull();
        // 离线模式下安全首帧回调一次空集合，消除冷态等待
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(receivedRef.get()).isEmpty();

        // 幂等自闭合，不报错
        sub.close();
        sub.close();
    }

    @Test
    @DisplayName("服务实例编程式动态订阅：首次立即派发、增量变更推送、MDC 链路穿透与注销闭环")
    void testSubscribeInstancesLifecycleAndMdc() throws Exception {
        NacosServiceManager serviceManager = mock(NacosServiceManager.class);
        NamingService namingService = mock(NamingService.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setServerAddr("127.0.0.1:8848");
        discoveryProperties.setGroup("DEFAULT_GROUP");

        when(serviceManager.getNamingService(any())).thenReturn(namingService);
        when(namingService.getServerStatus()).thenReturn("UP");

        // 模拟首帧快照
        Instance inst1 = createInstance("192.168.1.10", 8080, "zone-1", 1.0, true, true);
        when(namingService.selectInstances(eq("order-service"), eq("DEFAULT_GROUP"), eq(true)))
                .thenReturn(List.of(inst1));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("discoveryProperties", discoveryProperties);
        beanFactory.addBean("serviceManager", serviceManager);

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                new AtengNacosProperties(),
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        java.util.concurrent.atomic.AtomicInteger callbackCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicReference<List<Instance>> currentInstances = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> callbackTraceId = new java.util.concurrent.atomic.AtomicReference<>();

        // 1. 注册订阅
        AutoCloseable sub = template.subscribeInstances("order-service", instances -> {
            callbackCount.incrementAndGet();
            currentInstances.set(instances);
            callbackTraceId.set(MDC.get("traceId"));
        });

        // 验证首帧即时触发
        assertThat(callbackCount.get()).isEqualTo(1);
        assertThat(currentInstances.get()).hasSize(1);
        assertThat(currentInstances.get().get(0).getIp()).isEqualTo("192.168.1.10");
        assertThat(callbackTraceId.get()).isNotBlank();
        assertThat(MDC.get("traceId")).isNull(); // MDC 确定性清理

        // 捕获底层 Nacos EventListener
        ArgumentCaptor<com.alibaba.nacos.api.naming.listener.EventListener> listenerCaptor =
                ArgumentCaptor.forClass(com.alibaba.nacos.api.naming.listener.EventListener.class);
        verify(namingService).subscribe(eq("order-service"), eq("DEFAULT_GROUP"), listenerCaptor.capture());
        com.alibaba.nacos.api.naming.listener.EventListener nacosListener = listenerCaptor.getValue();
        assertThat(nacosListener).isNotNull();

        // 2. 模拟 Nacos 推送增量实例变更事件
        Instance inst2 = createInstance("192.168.1.20", 8080, "zone-1", 1.0, true, true);
        com.alibaba.nacos.api.naming.listener.NamingEvent event =
                new com.alibaba.nacos.api.naming.listener.NamingEvent("order-service", "DEFAULT_GROUP", "", List.of(inst1, inst2));
        nacosListener.onEvent(event);

        assertThat(callbackCount.get()).isEqualTo(2);
        assertThat(currentInstances.get()).hasSize(2);
        assertThat(MDC.get("traceId")).isNull();

        // 3. 自闭合注销
        sub.close();
        verify(namingService).unsubscribe(eq("order-service"), eq("DEFAULT_GROUP"), eq(nacosListener));
    }

    @Test
    @DisplayName("服务实例编程式动态订阅：支持集群列表过滤与受管 destroy 兜底释放")
    void testSubscribeInstancesWithClustersAndDestroy() throws Exception {
        NacosServiceManager serviceManager = mock(NacosServiceManager.class);
        NamingService namingService = mock(NamingService.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setServerAddr("127.0.0.1:8848");

        when(serviceManager.getNamingService(any())).thenReturn(namingService);
        when(namingService.getServerStatus()).thenReturn("UP");

        List<String> clusters = List.of("cluster-a", "cluster-b");
        when(namingService.selectInstances(eq("pay-service"), eq("PAY_GROUP"), eq(clusters), eq(true)))
                .thenReturn(Collections.emptyList());

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("discoveryProperties", discoveryProperties);
        beanFactory.addBean("serviceManager", serviceManager);

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                new AtengNacosProperties(),
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        AutoCloseable sub = template.subscribeInstances("pay-service", "PAY_GROUP", clusters, instances -> {});
        assertThat(template.getActiveSubscriptionCount()).isGreaterThanOrEqualTo(1);

        ArgumentCaptor<com.alibaba.nacos.api.naming.listener.EventListener> listenerCaptor =
                ArgumentCaptor.forClass(com.alibaba.nacos.api.naming.listener.EventListener.class);
        verify(namingService).subscribe(eq("pay-service"), eq("PAY_GROUP"), eq(clusters), listenerCaptor.capture());

        // 容器销毁时自动注销
        template.destroy();
        verify(namingService).unsubscribe(eq("pay-service"), eq("PAY_GROUP"), eq(clusters), eq(listenerCaptor.getValue()));
        assertThat(template.getActiveSubscriptionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("服务实例编程式动态订阅：支持仅指定集群列表（默认分组）重载")
    void testSubscribeInstancesClustersDefaultGroupOverload() throws Exception {
        NacosServiceManager serviceManager = mock(NacosServiceManager.class);
        NamingService namingService = mock(NamingService.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setServerAddr("127.0.0.1:8848");

        when(serviceManager.getNamingService(any())).thenReturn(namingService);
        when(namingService.getServerStatus()).thenReturn("UP");

        List<String> clusters = List.of("cluster-x");
        when(namingService.selectInstances(eq("order-service"), eq("DEFAULT_GROUP"), eq(clusters), eq(true)))
                .thenReturn(Collections.emptyList());

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("discoveryProperties", discoveryProperties);
        beanFactory.addBean("serviceManager", serviceManager);

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                new AtengNacosProperties(),
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        AutoCloseable sub = template.subscribeInstances("order-service", clusters, instances -> {});
        assertThat(template.getActiveSubscriptionCount()).isEqualTo(1);

        ArgumentCaptor<com.alibaba.nacos.api.naming.listener.EventListener> listenerCaptor =
                ArgumentCaptor.forClass(com.alibaba.nacos.api.naming.listener.EventListener.class);
        verify(namingService).subscribe(eq("order-service"), eq("DEFAULT_GROUP"), eq(clusters), listenerCaptor.capture());

        sub.close();
        verify(namingService).unsubscribe(eq("order-service"), eq("DEFAULT_GROUP"), eq(clusters), eq(listenerCaptor.getValue()));
        assertThat(template.getActiveSubscriptionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("服务实例编程式动态订阅：业务监听器异常物理隔离屏障")
    void testSubscribeInstancesExceptionBarrier() throws Exception {
        NacosServiceManager serviceManager = mock(NacosServiceManager.class);
        NamingService namingService = mock(NamingService.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setServerAddr("127.0.0.1:8848");

        when(serviceManager.getNamingService(any())).thenReturn(namingService);
        when(namingService.getServerStatus()).thenReturn("UP");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("discoveryProperties", discoveryProperties);
        beanFactory.addBean("serviceManager", serviceManager);

        NacosTemplate template = new NacosTemplate(
                beanFactory.getBeanProvider(NacosServiceManager.class),
                beanFactory.getBeanProvider(NacosDiscoveryProperties.class),
                new AtengNacosProperties(),
                mock(NacosMetadataProvider.class),
                beanFactory.getBeanProvider(NacosLifecycleStateMachine.class),
                new ZoneAffinityInstanceFilter()
        );

        // 监听器抛出严重异常
        AutoCloseable sub = template.subscribeInstances("err-service", instances -> {
            throw new RuntimeException("Simulated listener business explosion!");
        });

        ArgumentCaptor<com.alibaba.nacos.api.naming.listener.EventListener> listenerCaptor =
                ArgumentCaptor.forClass(com.alibaba.nacos.api.naming.listener.EventListener.class);
        verify(namingService).subscribe(eq("err-service"), anyString(), listenerCaptor.capture());

        // 触发变更事件，异常被内部屏障拦截，不向外抛出
        com.alibaba.nacos.api.naming.listener.NamingEvent event =
                new com.alibaba.nacos.api.naming.listener.NamingEvent("err-service", "DEFAULT_GROUP", "", Collections.emptyList());
        listenerCaptor.getValue().onEvent(event);

        sub.close();
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
