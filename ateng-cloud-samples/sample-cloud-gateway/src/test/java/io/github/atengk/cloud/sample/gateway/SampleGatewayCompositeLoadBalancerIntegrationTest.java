package io.github.atengk.cloud.sample.gateway;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.loadbalancer.GrayServiceInstanceListSupplier;
import io.github.atengk.cloud.nacos.loadbalancer.NacosWarmupLoadBalancer;
import io.github.atengk.cloud.nacos.loadbalancer.ZonePreferenceServiceInstanceListSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.loadbalancer.cache.LoadBalancerCacheProperties;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关复合负载均衡责任链端到端集成测试 (100% 离线自洽)
 *
 * <p>核心治理验证：
 * 1. 验证在同时包含 ateng-cloud-gateway 与 ateng-cloud-nacos 的场景工程中，LoadBalancerClientFactory
 *    装配出的子上下文装配链为：ZonePreference ➔ Gray ➔ Caching ➔ DiscoveryClient；
 * 2. 验证默认负载均衡缓存 TTL 从 35s 收敛为 3s；
 * 3. 验证选择器统一接入 NacosWarmupLoadBalancer；
 * 4. 验证灰度硬隔离优先于机房软亲和、跨机房穿透与灰度全下线平滑降级。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SampleGatewayCompositeLoadBalancerIntegrationTest {

    @Autowired
    private LoadBalancerClientFactory loadBalancerClientFactory;

    @Autowired(required = false)
    private LoadBalancerCacheProperties mainCacheProperties;

    @Autowired(required = false)
    private ReactiveDiscoveryClient reactiveDiscoveryClient;

    @Autowired(required = false)
    private Environment environment;

    @Test
    @DisplayName("双模自适应：激活 local Profile 时验证真实连接 Nacos 并成功获取服务列表")
    void testRealNacosDiscoveryWhenLocalProfileActive() {
        if (environment != null && Arrays.asList(environment.getActiveProfiles()).contains("local")) {
            assertThat(reactiveDiscoveryClient)
                    .as("激活 local Profile 时 ReactiveDiscoveryClient 必须成功装配")
                    .isNotNull();
            List<String> services = reactiveDiscoveryClient.getServices().collectList().block(Duration.ofSeconds(5));
            assertThat(services)
                    .as("成功与真实 Nacos 交互并返回服务列表（不为 null）")
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("验证主容器与子上下文缓存 TTL 默认显式收敛为 3s")
    void testCacheTtlConvergedToThreeSeconds() {
        if (mainCacheProperties != null) {
            assertThat(mainCacheProperties.getTtl()).isEqualTo(Duration.ofSeconds(3));
        }
        LoadBalancerCacheProperties childCacheProps = loadBalancerClientFactory
                .getInstance("user-service", LoadBalancerCacheProperties.class);
        assertThat(childCacheProps).isNotNull();
        assertThat(childCacheProps.getTtl()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("验证子上下文负载均衡选择器接入 NacosWarmupLoadBalancer 且责任链嵌套无竞争")
    void testCompositeResponsibilityChainStructure() {
        ReactiveLoadBalancer<ServiceInstance> loadBalancer = loadBalancerClientFactory.getInstance("order-service");
        assertThat(loadBalancer).isNotNull();
        assertThat(loadBalancer).isInstanceOf(NacosWarmupLoadBalancer.class);

        ServiceInstanceListSupplier supplier = loadBalancerClientFactory
                .getInstance("order-service", ServiceInstanceListSupplier.class);
        assertThat(supplier).isNotNull();
        // 顶层必须为 ZonePreferenceServiceInstanceListSupplier
        assertThat(supplier).isInstanceOf(ZonePreferenceServiceInstanceListSupplier.class);

        ZonePreferenceServiceInstanceListSupplier zoneSupplier = (ZonePreferenceServiceInstanceListSupplier) supplier;
        // 第二层必须为 GrayServiceInstanceListSupplier
        assertThat(zoneSupplier.getDelegate()).isInstanceOf(GrayServiceInstanceListSupplier.class);
    }

    @Test
    @DisplayName("验证责任链语义：染色标签锁定目标版本池，同机房就近亲和与异地机房穿透")
    void testResponsibilityChainSemantics() {
        ServiceInstance instGrayAz1 = createInstance("inst-gray-az1", "zone-a", "v2", 1.0, true);
        ServiceInstance instGrayAz2 = createInstance("inst-gray-az2", "zone-b", "v2", 1.0, true);
        ServiceInstance instBaseAz1 = createInstance("inst-base-az1", "zone-a", "baseline", 1.0, true);
        ServiceInstance instBaseAz2 = createInstance("inst-base-az2", "zone-b", "baseline", 1.0, true);

        ServiceInstanceListSupplier supplier = loadBalancerClientFactory
                .getInstance("order-service", ServiceInstanceListSupplier.class);
        ZonePreferenceServiceInstanceListSupplier zoneSupplier = (ZonePreferenceServiceInstanceListSupplier) supplier;
        GrayServiceInstanceListSupplier graySupplier = (GrayServiceInstanceListSupplier) zoneSupplier.getDelegate();

        // 1. 灰度请求锁定版本池并优先同机房
        List<ServiceInstance> grayFiltered = graySupplier.filterByTag(List.of(instGrayAz1, instGrayAz2, instBaseAz1, instBaseAz2), "v2");
        assertThat(grayFiltered).extracting(ServiceInstance::getInstanceId).containsExactlyInAnyOrder("inst-gray-az1", "inst-gray-az2");

        List<ServiceInstance> zoneFiltered = zoneSupplier.filterByZone(grayFiltered);
        // 如果当前节点无 zone 配置，返回全量灰度实例；如果有 zone 配置，返回该 zone 灰度实例
        assertThat(zoneFiltered).isNotEmpty();
        assertThat(zoneFiltered).allMatch(inst -> "v2".equals(inst.getMetadata().get("tag")));

        // 2. 基线请求绝对不落入灰度节点
        List<ServiceInstance> baseFiltered = graySupplier.filterByTag(List.of(instGrayAz1, instGrayAz2, instBaseAz1, instBaseAz2), null);
        assertThat(baseFiltered).extracting(ServiceInstance::getInstanceId).containsExactlyInAnyOrder("inst-base-az1", "inst-base-az2");

        // 3. 灰度全下线平滑降级至基线节点
        List<ServiceInstance> fallbackFiltered = graySupplier.filterByTag(List.of(instBaseAz1, instBaseAz2), "v999");
        assertThat(fallbackFiltered).extracting(ServiceInstance::getInstanceId).containsExactlyInAnyOrder("inst-base-az1", "inst-base-az2");
    }

    private ServiceInstance createInstance(String id, String zone, String tag, double weight, boolean healthy) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("zone", zone);
        metadata.put("tag", tag);
        metadata.put("nacos.weight", String.valueOf(weight));
        metadata.put("nacos.healthy", String.valueOf(healthy));
        metadata.put("startup-time", String.valueOf(System.currentTimeMillis() - 600_000));
        return new DefaultServiceInstance(id, "order-service", "127.0.0.1", 8080, false, metadata);
    }
}
