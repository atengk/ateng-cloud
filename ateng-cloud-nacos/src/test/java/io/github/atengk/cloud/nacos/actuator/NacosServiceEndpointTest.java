package io.github.atengk.cloud.nacos.actuator;

import io.github.atengk.cloud.nacos.lifecycle.NacosInstanceLifecycleState;
import io.github.atengk.cloud.nacos.lifecycle.NacosLifecycleStateMachine;
import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.client.serviceregistry.AbstractAutoServiceRegistration;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nacos Actuator 专属运维端点单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class NacosServiceEndpointTest {

    @Test
    @DisplayName("测试运维端点读操作返回完整状态与元数据")
    void testGetStatus() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.setZone("beijing-az1");
        properties.setCluster("default-cluster");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        NacosLifecycleStateMachine stateMachine = new NacosLifecycleStateMachine(
                properties, event -> {}, beanFactory.getBeanProvider(AbstractAutoServiceRegistration.class)
        );

        NacosMetadataProvider metadataProvider = new NacosMetadataProvider() {
            @Override
            public Map<String, String> getMetadata() {
                return Collections.singletonMap("version", "1.0.0");
            }

            @Override
            public void populateMetadata(Map<String, String> targetMetadata) {
                targetMetadata.put("version", "1.0.0");
            }
        };

        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "demo-service");

        NacosServiceEndpoint endpoint = new NacosServiceEndpoint(stateMachine, metadataProvider, properties, env);

        Map<String, Object> status = endpoint.getStatus();
        assertThat(status).containsEntry("status", "UP");
        assertThat(status).containsEntry("serviceName", "demo-service");
        assertThat(status).containsEntry("zone", "beijing-az1");
        assertThat(status).containsEntry("cluster", "default-cluster");
        assertThat(status).containsEntry("warmupDuration", "60s");
        assertThat(status).containsKey("warmupUptimeSeconds");
        assertThat(status).containsKey("warmupRemainingSeconds");
        assertThat(status).containsKey("warmupCompleted");
        @SuppressWarnings("unchecked")
        Map<String, String> metadata = (Map<String, String>) status.get("metadata");
        assertThat(metadata).containsEntry("version", "1.0.0");
    }

    @Test
    @DisplayName("测试运维端点写操作执行 offline 与 online 指令")
    void testExecuteAction() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.setDrainTimeout(Duration.ofMillis(10));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        NacosLifecycleStateMachine stateMachine = new NacosLifecycleStateMachine(
                properties, event -> {}, beanFactory.getBeanProvider(AbstractAutoServiceRegistration.class)
        );

        MockEnvironment env = new MockEnvironment();
        NacosServiceEndpoint endpoint = new NacosServiceEndpoint(stateMachine, null, properties, env);

        // 1. 发起 offline
        Map<String, Object> offlineResp = endpoint.executeAction("offline");
        assertThat(offlineResp).containsEntry("success", true);
        assertThat(offlineResp).containsEntry("state", NacosInstanceLifecycleState.OUT_OF_SERVICE.name());

        // 2. 发起 online
        Map<String, Object> onlineResp = endpoint.executeAction("online");
        assertThat(onlineResp).containsEntry("success", true);
        assertThat(onlineResp).containsEntry("state", NacosInstanceLifecycleState.UP.name());

        // 3. 发送非法指令
        Map<String, Object> invalidResp = endpoint.executeAction("restart");
        assertThat(invalidResp).containsEntry("success", false);
    }

    @Test
    @DisplayName("测试运维端点读取受管配置监听器与最近热更状态")
    void testGetStatusWithRefreshEngine() {
        AtengNacosProperties properties = new AtengNacosProperties();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        NacosLifecycleStateMachine stateMachine = new NacosLifecycleStateMachine(
                properties, event -> {}, beanFactory.getBeanProvider(AbstractAutoServiceRegistration.class)
        );

        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "demo-service");

        io.github.atengk.cloud.nacos.spi.NacosConfigChangeListener listener = new io.github.atengk.cloud.nacos.spi.NacosConfigChangeListener() {
            @Override
            public void onConfigChange(io.github.atengk.cloud.nacos.event.SafeConfigRefreshEvent event) {}

            @Override
            public String getPrefix() {
                return "demo.prefix";
            }

            @Override
            public int getOrder() {
                return 10;
            }
        };

        io.github.atengk.cloud.nacos.refresh.NacosConfigRefreshEngine refreshEngine =
                new io.github.atengk.cloud.nacos.refresh.NacosConfigRefreshEngine(
                        env,
                        event -> {},
                        java.util.List.of(listener),
                        null,
                        properties
                );

        // 触发一次模拟热更以生成 lastRefreshRecord
        refreshEngine.refreshConfig(java.util.Set.of("demo.prefix.key"), "demo.yml", "DEFAULT_GROUP");

        NacosServiceEndpoint endpoint = new NacosServiceEndpoint(stateMachine, null, properties, env, refreshEngine);
        Map<String, Object> status = endpoint.getStatus();

        assertThat(status).containsKey("config");
        @SuppressWarnings("unchecked")
        Map<String, Object> configInfo = (Map<String, Object>) status.get("config");
        assertThat(configInfo).containsEntry("enforceBeanImmutability", true);

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> listeners = (java.util.List<Map<String, Object>>) configInfo.get("listeners");
        assertThat(listeners).hasSize(1);
        assertThat(listeners.get(0)).containsEntry("prefix", "demo.prefix");
        assertThat(listeners.get(0)).containsEntry("order", 10);

        @SuppressWarnings("unchecked")
        Map<String, Object> lastRefresh = (Map<String, Object>) configInfo.get("lastRefresh");
        assertThat(lastRefresh).isNotNull();
        assertThat(lastRefresh).containsEntry("success", true);
        assertThat(lastRefresh.get("traceId")).isNotNull();
    }
}
