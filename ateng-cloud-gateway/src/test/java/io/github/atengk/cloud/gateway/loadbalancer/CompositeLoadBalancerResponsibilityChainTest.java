package io.github.atengk.cloud.gateway.loadbalancer;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
import io.github.atengk.cloud.nacos.loadbalancer.AdaptiveInstanceWeightCalculator;
import io.github.atengk.cloud.nacos.loadbalancer.NacosWarmupLoadBalancer;
import io.github.atengk.cloud.nacos.loadbalancer.ZonePreferenceServiceInstanceListSupplier;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复合负载均衡责任链对抗性黑盒集成单元测试
 *
 * <p>核心断言：
 * 1. 业务隔离级优先于拓扑亲和级：先根据流量染色标签锁定版本池，再在版本池内执行同机房亲和与跨机房容灾；
 * 2. 灰度节点在异地机房时的穿透调度能力：本地机房无灰度节点时跨区调度异地灰度节点，绝不污染本地基线；
 * 3. 灰度全下线时的无损级联降级：灰度全下线时平滑降级至本地机房基线实例；
 * 4. 0 权重摘流与不健康节点的刚性剔除；
 * 5. 统一接入 NacosWarmupLoadBalancer 平滑预热与自适应加权。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
class CompositeLoadBalancerResponsibilityChainTest {

    private ServiceInstance createInstance(String id, String host, int port, String zone, String tag, double weight, boolean healthy) {
        Map<String, String> metadata = new HashMap<>();
        if (zone != null) {
            metadata.put("zone", zone);
        }
        if (tag != null) {
            metadata.put("tag", tag);
        }
        metadata.put("nacos.weight", String.valueOf(weight));
        metadata.put("nacos.healthy", String.valueOf(healthy));
        metadata.put("startup-time", String.valueOf(System.currentTimeMillis() - 600_000)); // 已完全预热
        return new DefaultServiceInstance(id, "test-service", host, port, false, metadata);
    }

    private ServiceInstance createColdInstance(String id, String host, int port, String zone, String tag, double weight, boolean healthy) {
        Map<String, String> metadata = new HashMap<>();
        if (zone != null) {
            metadata.put("zone", zone);
        }
        if (tag != null) {
            metadata.put("tag", tag);
        }
        metadata.put("nacos.weight", String.valueOf(weight));
        metadata.put("nacos.healthy", String.valueOf(healthy));
        metadata.put("startup-time", String.valueOf(System.currentTimeMillis() - 5_000)); // 启动 5s (冷启动中)
        metadata.put("warmup-duration", "60s");
        return new DefaultServiceInstance(id, "test-service", host, port, false, metadata);
    }

    private DefaultRequest<RequestDataContext> createTagRequest(String tag) {
        HttpHeaders headers = new HttpHeaders();
        if (tag != null) {
            headers.set(GatewayConstants.X_TRAFFIC_TAG, tag);
        }
        MultiValueMap<String, String> cookies = new LinkedMultiValueMap<>();
        Map<String, Object> attributes = new HashMap<>();
        RequestData requestData = new RequestData(HttpMethod.GET, URI.create("http://localhost/test"), headers, cookies, attributes);
        return new DefaultRequest<>(new RequestDataContext(requestData));
    }

    private ServiceInstanceListSupplier createStubSupplier(List<ServiceInstance> instances) {
        return new ServiceInstanceListSupplier() {
            @Override
            public String getServiceId() {
                return "test-service";
            }

            @Override
            public Flux<List<ServiceInstance>> get() {
                return Flux.just(instances);
            }
        };
    }

    private ServiceInstanceListSupplier buildCompositeChain(List<ServiceInstance> instances,
                                                            AtengGatewayTrafficProperties trafficProperties,
                                                            AtengNacosProperties nacosProperties,
                                                            MockEnvironment env) {
        // 1. 基础发现提供者
        ServiceInstanceListSupplier baseSupplier = createStubSupplier(instances);
        // 2. 灰度隔离提供者
        ServiceInstanceListSupplier graySupplier = new GrayServiceInstanceListSupplier(baseSupplier, trafficProperties);
        // 3. 同机房就近亲和与跨区容灾提供者
        return new ZonePreferenceServiceInstanceListSupplier(graySupplier, nacosProperties, env);
    }

    @Test
    @DisplayName("场景一：灰度标签命中同机房灰度节点，同时排除 0 权重与不健康节点")
    void testGrayTagAndSameZoneAffinity() {
        ServiceInstance instGrayAz1 = createInstance("inst-gray-az1", "10.0.1.1", 8080, "zone-a", "v2", 1.0, true);
        ServiceInstance instGrayAz2 = createInstance("inst-gray-az2", "10.0.2.1", 8080, "zone-b", "v2", 1.0, true);
        ServiceInstance instBaseAz1 = createInstance("inst-base-az1", "10.0.1.2", 8080, "zone-a", "baseline", 1.0, true);
        ServiceInstance instBaseAz2 = createInstance("inst-base-az2", "10.0.2.2", 8080, "zone-b", "baseline", 1.0, true);
        ServiceInstance instZeroWeight = createInstance("inst-zero", "10.0.1.3", 8080, "zone-a", "v2", 0.0, true);
        ServiceInstance instUnhealthy = createInstance("inst-unhealthy", "10.0.1.4", 8080, "zone-a", "v2", 1.0, false);

        List<ServiceInstance> all = List.of(instGrayAz1, instGrayAz2, instBaseAz1, instBaseAz2, instZeroWeight, instUnhealthy);

        AtengGatewayTrafficProperties trafficProps = new AtengGatewayTrafficProperties();
        trafficProps.setEnabled(true);

        AtengNacosProperties nacosProps = new AtengNacosProperties();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.cloud.loadbalancer.zone", "zone-a");

        ServiceInstanceListSupplier chain = buildCompositeChain(all, trafficProps, nacosProps, env);

        List<ServiceInstance> selected = chain.get(createTagRequest("v2")).blockFirst();
        assertThat(selected).isNotNull();
        // 严格断言：只命中 zone-a 的健康且权重有效的灰度节点 inst-gray-az1
        assertThat(selected).hasSize(1)
                .extracting(ServiceInstance::getInstanceId)
                .containsExactly("inst-gray-az1");
    }

    @Test
    @DisplayName("场景二：业务隔离级高于拓扑亲和级，本地机房无灰度节点时穿透调度异地机房灰度节点")
    void testCrossZoneGrayPenetration() {
        // 本地 zone-a 只有基线节点，异地 zone-b 有灰度节点
        ServiceInstance instGrayAz2 = createInstance("inst-gray-az2", "10.0.2.1", 8080, "zone-b", "v2", 1.0, true);
        ServiceInstance instBaseAz1 = createInstance("inst-base-az1", "10.0.1.2", 8080, "zone-a", "baseline", 1.0, true);
        ServiceInstance instBaseAz2 = createInstance("inst-base-az2", "10.0.2.2", 8080, "zone-b", "baseline", 1.0, true);

        List<ServiceInstance> all = List.of(instGrayAz2, instBaseAz1, instBaseAz2);

        AtengGatewayTrafficProperties trafficProps = new AtengGatewayTrafficProperties();
        trafficProps.setEnabled(true);

        AtengNacosProperties nacosProps = new AtengNacosProperties();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.cloud.loadbalancer.zone", "zone-a"); // 调用方在 zone-a

        ServiceInstanceListSupplier chain = buildCompositeChain(all, trafficProps, nacosProps, env);

        List<ServiceInstance> selected = chain.get(createTagRequest("v2")).blockFirst();
        assertThat(selected).isNotNull();
        // 严格断言：穿透调度至 zone-b 的灰度实例，绝不降级至本地 zone-a 的基线实例
        assertThat(selected).hasSize(1)
                .extracting(ServiceInstance::getInstanceId)
                .containsExactly("inst-gray-az2");
    }

    @Test
    @DisplayName("场景三：灰度节点全下线且 fallbackToDefault=true 时平滑降级至本机房基线实例")
    void testGrayAllDownFallbackToSameZoneBaseline() {
        // 集群无任何 v2 灰度节点，仅有基线节点
        ServiceInstance instBaseAz1 = createInstance("inst-base-az1", "10.0.1.2", 8080, "zone-a", "baseline", 1.0, true);
        ServiceInstance instBaseAz2 = createInstance("inst-base-az2", "10.0.2.2", 8080, "zone-b", "baseline", 1.0, true);

        List<ServiceInstance> all = List.of(instBaseAz1, instBaseAz2);

        AtengGatewayTrafficProperties trafficProps = new AtengGatewayTrafficProperties();
        trafficProps.setEnabled(true);
        trafficProps.setFallbackToDefault(true); // 允许平滑降级

        AtengNacosProperties nacosProps = new AtengNacosProperties();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.cloud.loadbalancer.zone", "zone-a"); // 调用方在 zone-a

        ServiceInstanceListSupplier chain = buildCompositeChain(all, trafficProps, nacosProps, env);

        List<ServiceInstance> selected = chain.get(createTagRequest("v2")).blockFirst();
        assertThat(selected).isNotNull();
        // 严格断言：降级到基线池后，拓扑亲和级优先命中同机房基线节点 inst-base-az1
        assertThat(selected).hasSize(1)
                .extracting(ServiceInstance::getInstanceId)
                .containsExactly("inst-base-az1");
    }

    @Test
    @DisplayName("场景四：灰度节点全下线且 fallbackToDefault=false 时阻断流量返回空")
    void testGrayAllDownWithoutFallbackReturnsEmpty() {
        ServiceInstance instBaseAz1 = createInstance("inst-base-az1", "10.0.1.2", 8080, "zone-a", "baseline", 1.0, true);
        ServiceInstance instBaseAz2 = createInstance("inst-base-az2", "10.0.2.2", 8080, "zone-b", "baseline", 1.0, true);

        List<ServiceInstance> all = List.of(instBaseAz1, instBaseAz2);

        AtengGatewayTrafficProperties trafficProps = new AtengGatewayTrafficProperties();
        trafficProps.setEnabled(true);
        trafficProps.setFallbackToDefault(false); // 严禁降级

        AtengNacosProperties nacosProps = new AtengNacosProperties();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.cloud.loadbalancer.zone", "zone-a");

        ServiceInstanceListSupplier chain = buildCompositeChain(all, trafficProps, nacosProps, env);

        List<ServiceInstance> selected = chain.get(createTagRequest("v2")).blockFirst();
        assertThat(selected).isNotNull();
        assertThat(selected).isEmpty();
    }

    @Test
    @DisplayName("场景五：基线普通请求优先路由至本机房基线实例，绝不误入金丝雀灰度节点")
    void testBaselineRequestNeverTouchesGray() {
        ServiceInstance instGrayAz1 = createInstance("inst-gray-az1", "10.0.1.1", 8080, "zone-a", "v2", 1.0, true);
        ServiceInstance instGrayAz2 = createInstance("inst-gray-az2", "10.0.2.1", 8080, "zone-b", "v2", 1.0, true);
        ServiceInstance instBaseAz1 = createInstance("inst-base-az1", "10.0.1.2", 8080, "zone-a", "baseline", 1.0, true);
        ServiceInstance instBaseAz2 = createInstance("inst-base-az2", "10.0.2.2", 8080, "zone-b", "baseline", 1.0, true);

        List<ServiceInstance> all = List.of(instGrayAz1, instGrayAz2, instBaseAz1, instBaseAz2);

        AtengGatewayTrafficProperties trafficProps = new AtengGatewayTrafficProperties();
        trafficProps.setEnabled(true);

        AtengNacosProperties nacosProps = new AtengNacosProperties();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.cloud.loadbalancer.zone", "zone-a");

        ServiceInstanceListSupplier chain = buildCompositeChain(all, trafficProps, nacosProps, env);

        // 未携带染色标头的基线请求
        List<ServiceInstance> selected = chain.get(createTagRequest(null)).blockFirst();
        assertThat(selected).isNotNull();
        assertThat(selected).hasSize(1)
                .extracting(ServiceInstance::getInstanceId)
                .containsExactly("inst-base-az1");
    }

    @Test
    @DisplayName("场景六：基线普通请求在本地基线宕机时平滑跨区降级至异地基线实例，绝不误入本地灰度节点")
    void testBaselineCrossZoneFallbackNeverTouchesLocalGray() {
        ServiceInstance instGrayAz1 = createInstance("inst-gray-az1", "10.0.1.1", 8080, "zone-a", "v2", 1.0, true);
        ServiceInstance instBaseAz2 = createInstance("inst-base-az2", "10.0.2.2", 8080, "zone-b", "baseline", 1.0, true);

        List<ServiceInstance> all = List.of(instGrayAz1, instBaseAz2);

        AtengGatewayTrafficProperties trafficProps = new AtengGatewayTrafficProperties();
        trafficProps.setEnabled(true);

        AtengNacosProperties nacosProps = new AtengNacosProperties();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.cloud.loadbalancer.zone", "zone-a");

        ServiceInstanceListSupplier chain = buildCompositeChain(all, trafficProps, nacosProps, env);

        List<ServiceInstance> selected = chain.get(createTagRequest(null)).blockFirst();
        assertThat(selected).isNotNull();
        // 严格断言：即使本地只有灰度节点，基线流量也跨区调度至异地基线，绝不落入本地灰度
        assertThat(selected).hasSize(1)
                .extracting(ServiceInstance::getInstanceId)
                .containsExactly("inst-base-az2");
    }

    @Test
    @DisplayName("场景七：与 NacosWarmupLoadBalancer 协同，平滑接入冷启动预热加权调度")
    void testEndToEndWithNacosWarmupLoadBalancer() {
        ServiceInstance matureInst = createInstance("inst-mature", "10.0.1.1", 8080, "zone-a", "v2", 1.0, true);
        ServiceInstance coldInst = createColdInstance("inst-cold", "10.0.1.2", 8080, "zone-a", "v2", 1.0, true);

        List<ServiceInstance> all = List.of(matureInst, coldInst);

        AtengGatewayTrafficProperties trafficProps = new AtengGatewayTrafficProperties();
        trafficProps.setEnabled(true);

        AtengNacosProperties nacosProps = new AtengNacosProperties();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.cloud.loadbalancer.zone", "zone-a");

        ServiceInstanceListSupplier chain = buildCompositeChain(all, trafficProps, nacosProps, env);

        ObjectProvider<ServiceInstanceListSupplier> supplierProvider = new ObjectProvider<>() {
            @Override
            public ServiceInstanceListSupplier getObject(Object... args) {
                return chain;
            }

            @Override
            public ServiceInstanceListSupplier getIfAvailable() {
                return chain;
            }

            @Override
            public ServiceInstanceListSupplier getIfUnique() {
                return chain;
            }

            @Override
            public ServiceInstanceListSupplier getObject() {
                return chain;
            }
        };

        NacosWarmupLoadBalancer loadBalancer = new NacosWarmupLoadBalancer("test-service", supplierProvider, nacosProps, null);

        // 多次调用选择器，验证正常选取到实例且无异常
        for (int i = 0; i < 20; i++) {
            Response<ServiceInstance> response = loadBalancer.choose(createTagRequest("v2")).block();
            assertThat(response).isNotNull();
            assertThat(response.hasServer()).isTrue();
            assertThat(response.getServer().getInstanceId()).isIn("inst-mature", "inst-cold");
        }
    }

    @Test
    @DisplayName("场景八：与 AdaptiveInstanceWeightCalculator 自适应过载加权协同调度")
    void testEndToEndWithAdaptiveWeightCalculator() {
        ServiceInstance instNormal = createInstance("inst-normal", "10.0.1.1", 8080, "zone-a", "v2", 1.0, true);
        // 过载节点：CPU 95%
        Map<String, String> metaOverload = new HashMap<>(instNormal.getMetadata());
        metaOverload.put("cpu-usage", "0.95");
        ServiceInstance instOverload = new DefaultServiceInstance("inst-overload", "test-service", "10.0.1.2", 8080, false, metaOverload);

        List<ServiceInstance> all = List.of(instNormal, instOverload);

        AtengGatewayTrafficProperties trafficProps = new AtengGatewayTrafficProperties();
        trafficProps.setEnabled(true);

        AtengNacosProperties nacosProps = new AtengNacosProperties();
        nacosProps.getLoadbalancer().getAdaptiveWeight().setEnabled(true);
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.cloud.loadbalancer.zone", "zone-a");

        ServiceInstanceListSupplier chain = buildCompositeChain(all, trafficProps, nacosProps, env);

        ObjectProvider<ServiceInstanceListSupplier> supplierProvider = new ObjectProvider<>() {
            @Override
            public ServiceInstanceListSupplier getObject(Object... args) {
                return chain;
            }

            @Override
            public ServiceInstanceListSupplier getIfAvailable() {
                return chain;
            }

            @Override
            public ServiceInstanceListSupplier getIfUnique() {
                return chain;
            }

            @Override
            public ServiceInstanceListSupplier getObject() {
                return chain;
            }
        };

        AdaptiveInstanceWeightCalculator adaptiveCalculator = new AdaptiveInstanceWeightCalculator(nacosProps);
        ObjectProvider<io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator> calculatorProvider = new ObjectProvider<>() {
            @Override
            public io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator getObject(Object... args) {
                return adaptiveCalculator;
            }

            @Override
            public io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator getIfAvailable() {
                return adaptiveCalculator;
            }

            @Override
            public io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator getIfUnique() {
                return adaptiveCalculator;
            }

            @Override
            public io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator getObject() {
                return adaptiveCalculator;
            }
        };

        NacosWarmupLoadBalancer loadBalancer = new NacosWarmupLoadBalancer("test-service", supplierProvider, nacosProps, calculatorProvider);

        Response<ServiceInstance> response = loadBalancer.choose(createTagRequest("v2")).block();
        assertThat(response).isNotNull();
        assertThat(response.hasServer()).isTrue();
    }
}
