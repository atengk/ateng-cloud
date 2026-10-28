package io.github.atengk.cloud.nacos.loadbalancer;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ZonePreferenceServiceInstanceListSupplier} 单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
class ZonePreferenceServiceInstanceListSupplierTest {

    @Test
    @DisplayName("空安全：实例列表为 null 或空时返回空集合")
    void testEmptySafety() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.setZone("beijing-az1");

        ZonePreferenceServiceInstanceListSupplier supplier = new ZonePreferenceServiceInstanceListSupplier(
                new StubServiceInstanceListSupplier("order-service", Collections.emptyList()),
                properties);

        assertThat(supplier.filterByZone(null)).isEmpty();
        assertThat(supplier.filterByZone(Collections.emptyList())).isEmpty();
    }

    @Test
    @DisplayName("同机房亲和优先：优先筛选与 targetZone 匹配的实例")
    void testZoneAffinityHit() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.setZone("beijing-az1");

        ServiceInstance inst1 = createInstance("inst-1", "192.168.1.10", 8080, "beijing-az1", 1.0, true);
        ServiceInstance inst2 = createInstance("inst-2", "192.168.1.11", 8080, "beijing-az1", 1.0, true);
        ServiceInstance inst3 = createInstance("inst-3", "192.168.2.10", 8080, "shanghai-az1", 1.0, true);

        ZonePreferenceServiceInstanceListSupplier supplier = new ZonePreferenceServiceInstanceListSupplier(
                new StubServiceInstanceListSupplier("order-service", List.of(inst1, inst2, inst3)),
                properties);

        List<ServiceInstance> result = supplier.filterByZone(List.of(inst1, inst2, inst3));
        assertThat(result).hasSize(2)
                .extracting(ServiceInstance::getInstanceId)
                .containsExactlyInAnyOrder("inst-1", "inst-2");

        // 验证 Flux 响应式接口
        List<ServiceInstance> fluxResult = supplier.get().blockFirst();
        assertThat(fluxResult).hasSize(2)
                .extracting(ServiceInstance::getInstanceId)
                .containsExactlyInAnyOrder("inst-1", "inst-2");
    }

    @Test
    @DisplayName("跨区平滑降级：本机房无可用实例时平滑降级返回全集群健康实例")
    void testCrossZoneFallback() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.setZone("beijing-az2"); // 当前机房无实例

        ServiceInstance inst1 = createInstance("inst-1", "192.168.1.10", 8080, "beijing-az1", 1.0, true);
        ServiceInstance inst2 = createInstance("inst-2", "192.168.2.10", 8080, "shanghai-az1", 1.0, true);

        ZonePreferenceServiceInstanceListSupplier supplier = new ZonePreferenceServiceInstanceListSupplier(
                new StubServiceInstanceListSupplier("order-service", List.of(inst1, inst2)),
                properties);

        List<ServiceInstance> result = supplier.filterByZone(List.of(inst1, inst2));
        assertThat(result).hasSize(2)
                .extracting(ServiceInstance::getInstanceId)
                .containsExactlyInAnyOrder("inst-1", "inst-2");
    }

    @Test
    @DisplayName("权重与健康过滤：排除 weight <= 0.0 或 unhealthy 实例")
    void testFilterUnroutableInstances() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.setZone("beijing-az1");

        ServiceInstance normalInst = createInstance("inst-1", "192.168.1.10", 8080, "beijing-az1", 1.0, true);
        ServiceInstance zeroWeightInst = createInstance("inst-2", "192.168.1.11", 8080, "beijing-az1", 0.0, true);
        ServiceInstance unhealthyInst = createInstance("inst-3", "192.168.1.12", 8080, "beijing-az1", 1.0, false);

        ZonePreferenceServiceInstanceListSupplier supplier = new ZonePreferenceServiceInstanceListSupplier(
                new StubServiceInstanceListSupplier("order-service", List.of(normalInst, zeroWeightInst, unhealthyInst)),
                properties);

        List<ServiceInstance> result = supplier.filterByZone(List.of(normalInst, zeroWeightInst, unhealthyInst));
        assertThat(result).hasSize(1)
                .extracting(ServiceInstance::getInstanceId)
                .containsExactly("inst-1");
    }

    @Test
    @DisplayName("开关关闭：当 zoneAffinity=false 时原样返回所有可路由实例")
    void testZoneAffinityDisabled() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.setZone("beijing-az1");
        properties.getLoadbalancer().setZoneAffinity(false);

        ServiceInstance inst1 = createInstance("inst-1", "192.168.1.10", 8080, "beijing-az1", 1.0, true);
        ServiceInstance inst2 = createInstance("inst-2", "192.168.2.10", 8080, "shanghai-az1", 1.0, true);

        ZonePreferenceServiceInstanceListSupplier supplier = new ZonePreferenceServiceInstanceListSupplier(
                new StubServiceInstanceListSupplier("order-service", List.of(inst1, inst2)),
                properties);

        List<ServiceInstance> result = supplier.filterByZone(List.of(inst1, inst2));
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("环境抽象：优先从 Spring Environment 解析 ZONE 与 REGION")
    void testResolveZoneFromEnvironment() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("ZONE", "hangzhou-az1");

        AtengNacosProperties properties = new AtengNacosProperties();
        // properties 未显式配置 zone 时，回退从 environment 读取
        ZonePreferenceServiceInstanceListSupplier supplier = new ZonePreferenceServiceInstanceListSupplier(
                new StubServiceInstanceListSupplier("order-service", Collections.emptyList()),
                properties,
                env
        );

        assertThat(supplier.resolveTargetZone()).isEqualTo("hangzhou-az1");
    }

    private ServiceInstance createInstance(String id, String host, int port, String zone, double weight, boolean healthy) {
        Map<String, String> metadata = new HashMap<>();
        if (zone != null) {
            metadata.put("zone", zone);
        }
        metadata.put("nacos.weight", String.valueOf(weight));
        metadata.put("nacos.healthy", String.valueOf(healthy));
        return new DefaultServiceInstance(id, "order-service", host, port, false, metadata);
    }

    private static class StubServiceInstanceListSupplier implements ServiceInstanceListSupplier {
        private final String serviceId;
        private final List<ServiceInstance> instances;

        StubServiceInstanceListSupplier(String serviceId, List<ServiceInstance> instances) {
            this.serviceId = serviceId;
            this.instances = instances;
        }

        @Override
        public String getServiceId() {
            return serviceId;
        }

        @Override
        public Flux<List<ServiceInstance>> get() {
            return Flux.just(instances);
        }
    }
}
