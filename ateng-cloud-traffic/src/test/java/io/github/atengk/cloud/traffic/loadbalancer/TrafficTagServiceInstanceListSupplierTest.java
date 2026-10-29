package io.github.atengk.cloud.traffic.loadbalancer;

import io.github.atengk.cloud.traffic.code.TrafficResultCode;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.exception.TrafficRoutingException;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.DefaultRequestContext;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流量标签实例选择器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class TrafficTagServiceInstanceListSupplierTest {

    private AtengTrafficProperties properties;
    private ServiceInstance baseInstance1;
    private ServiceInstance baseInstance2;
    private ServiceInstance grayInstance1;
    private ServiceInstance grayInstance2;
    private ServiceInstance v3Instance;

    @BeforeEach
    void setUp() {
        properties = new AtengTrafficProperties();
        TrafficContextHolder.clear();
        MDC.clear();

        // 基线实例 1 (无 tag 元数据)
        baseInstance1 = new DefaultServiceInstance("inst-base-1", "user-service", "192.168.1.10", 8080, false, Collections.emptyMap());
        // 基线实例 2 (tag=default)
        baseInstance2 = new DefaultServiceInstance("inst-base-2", "user-service", "192.168.1.11", 8080, false, Map.of("tag", "default"));
        // 灰度实例 1 (tag=canary)
        grayInstance1 = new DefaultServiceInstance("inst-gray-1", "user-service", "192.168.1.20", 8080, false, Map.of("tag", "canary"));
        // 灰度实例 2 (兼容 traffic-tag=canary)
        grayInstance2 = new DefaultServiceInstance("inst-gray-2", "user-service", "192.168.1.21", 8080, false, Map.of("traffic-tag", "canary"));
        // 其他版本实例 (gray=v3)
        v3Instance = new DefaultServiceInstance("inst-v3-1", "user-service", "192.168.1.30", 8080, false, Map.of("gray", "v3"));
    }

    @AfterEach
    void tearDown() {
        TrafficContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试未染色请求优先调度至基线实例")
    void testBaselineRoutingWithoutTag() {
        List<ServiceInstance> allInstances = List.of(baseInstance1, baseInstance2, grayInstance1, grayInstance2, v3Instance);
        TrafficTagServiceInstanceListSupplier supplier = createSupplier(allInstances);

        List<ServiceInstance> result = supplier.filterByTag(allInstances, null);

        assertThat(result).hasSize(2)
                .containsExactlyInAnyOrder(baseInstance1, baseInstance2);
    }

    @Test
    @DisplayName("测试携带 canary 标签时精准筛选灰度实例 (兼容 tag 与 traffic-tag)")
    void testFilterByCanaryTag() {
        List<ServiceInstance> allInstances = List.of(baseInstance1, baseInstance2, grayInstance1, grayInstance2, v3Instance);
        TrafficTagServiceInstanceListSupplier supplier = createSupplier(allInstances);

        List<ServiceInstance> result = supplier.filterByTag(allInstances, "canary");

        assertThat(result).hasSize(2)
                .containsExactlyInAnyOrder(grayInstance1, grayInstance2);
    }

    @Test
    @DisplayName("测试携带 v3 标签精准筛选对应实例")
    void testFilterByV3Tag() {
        List<ServiceInstance> allInstances = List.of(baseInstance1, baseInstance2, grayInstance1, grayInstance2, v3Instance);
        TrafficTagServiceInstanceListSupplier supplier = createSupplier(allInstances);

        List<ServiceInstance> result = supplier.filterByTag(allInstances, "v3");

        assertThat(result).hasSize(1)
                .containsExactly(v3Instance);
    }

    @Test
    @DisplayName("测试灰度实例全下线时默认平滑降级至基线 (fallbackToDefault=true)")
    void testFallbackToDefaultWhenGrayOffline() {
        properties.setFallbackToDefault(true);
        // 只有基线实例在线
        List<ServiceInstance> availableInstances = List.of(baseInstance1, baseInstance2);
        TrafficTagServiceInstanceListSupplier supplier = createSupplier(availableInstances);

        List<ServiceInstance> result = supplier.filterByTag(availableInstances, "canary");

        // 平滑回退基线实例，杜绝 503
        assertThat(result).hasSize(2)
                .containsExactlyInAnyOrder(baseInstance1, baseInstance2);
    }

    @Test
    @DisplayName("测试关闭平滑降级时灰度全下线返回空列表")
    void testNoFallbackReturnsEmpty() {
        properties.setFallbackToDefault(false);
        List<ServiceInstance> availableInstances = List.of(baseInstance1, baseInstance2);
        TrafficTagServiceInstanceListSupplier supplier = createSupplier(availableInstances);

        List<ServiceInstance> result = supplier.filterByTag(availableInstances, "canary");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("测试金融级严格隔离模式 (strictLane=true) 阻断未匹配流量并抛出 TrafficRoutingException")
    void testStrictLaneIsolation() {
        properties.setStrictLane(true);
        properties.setFallbackToDefault(true); // 即使开启了降级，strictLane 优先级更高
        List<ServiceInstance> availableInstances = List.of(baseInstance1, baseInstance2);
        TrafficTagServiceInstanceListSupplier supplier = createSupplier(availableInstances);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> supplier.filterByTag(availableInstances, "non-existent-tag"))
                .isInstanceOf(TrafficRoutingException.class)
                .satisfies(ex -> {
                    TrafficRoutingException routingEx = (TrafficRoutingException) ex;
                    assertThat(routingEx.getCode()).isEqualTo(TrafficResultCode.STRICT_LANE_BLOCK.getCode());
                    assertThat(routingEx.getTag()).isEqualTo("non-existent-tag");
                    assertThat(routingEx.getServiceId()).isEqualTo("user-service");
                });
    }

    @Test
    @DisplayName("测试金融级严格隔离模式在下游全量实例集为空时依然立即阻断抛出 TrafficRoutingException")
    void testStrictLaneIsolationWhenInstancesEmpty() {
        properties.setStrictLane(true);
        TrafficTagServiceInstanceListSupplier supplier = createSupplier(Collections.emptyList());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> supplier.filterByTag(Collections.emptyList(), "canary"))
                .isInstanceOf(TrafficRoutingException.class)
                .satisfies(ex -> {
                    TrafficRoutingException routingEx = (TrafficRoutingException) ex;
                    assertThat(routingEx.getCode()).isEqualTo(TrafficResultCode.STRICT_LANE_BLOCK.getCode());
                    assertThat(routingEx.getTag()).isEqualTo("canary");
                    assertThat(routingEx.getServiceId()).isEqualTo("user-service");
                });

        // 当 tag 为空且 instances 为空时，返回空列表不阻断
        List<ServiceInstance> emptyResult = supplier.filterByTag(Collections.emptyList(), null);
        assertThat(emptyResult).isEmpty();
    }

    @Test
    @DisplayName("测试集群全量节点均带标签时，未染色请求保底返回全量")
    void testNoBaselineInstancesFallbackToAll() {
        List<ServiceInstance> allTaggedInstances = List.of(grayInstance1, v3Instance);
        TrafficTagServiceInstanceListSupplier supplier = createSupplier(allTaggedInstances);

        List<ServiceInstance> result = supplier.filterByTag(allTaggedInstances, null);

        assertThat(result).hasSize(2)
                .containsExactlyInAnyOrder(grayInstance1, v3Instance);
    }

    @Test
    @DisplayName("测试关闭功能时放行全量实例")
    void testDisabledFilter() {
        properties.setEnabled(false);
        List<ServiceInstance> allInstances = List.of(baseInstance1, grayInstance1);
        TrafficTagServiceInstanceListSupplier supplier = createSupplier(allInstances);

        List<ServiceInstance> result = supplier.filterByTag(allInstances, "canary");

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("测试从 TrafficContextHolder 响应式流 get() 过滤")
    void testReactiveGetFromTrafficContextHolder() {
        TrafficContextHolder.setTag("canary");
        List<ServiceInstance> allInstances = List.of(baseInstance1, grayInstance1);
        TrafficTagServiceInstanceListSupplier supplier = createSupplier(allInstances);

        List<ServiceInstance> result = supplier.get().blockFirst();

        assertThat(result).hasSize(1).containsExactly(grayInstance1);
    }

    @Test
    @DisplayName("测试从 RequestDataContext HTTP Header 响应式流 get(Request) 过滤")
    void testReactiveGetFromRequestHeader() {
        List<ServiceInstance> allInstances = List.of(baseInstance1, grayInstance1);
        TrafficTagServiceInstanceListSupplier supplier = createSupplier(allInstances);

        HttpHeaders headers = new HttpHeaders();
        headers.add(TrafficConstants.HEADER_TRAFFIC_TAG, "canary");
        RequestData requestData = new RequestData(HttpMethod.GET, URI.create("http://user-service/api"), headers, new org.springframework.util.LinkedMultiValueMap<>(), Collections.emptyMap());
        RequestDataContext requestDataContext = new RequestDataContext(requestData);
        DefaultRequest<RequestDataContext> request = new DefaultRequest<>(requestDataContext);

        List<ServiceInstance> result = supplier.get(request).blockFirst();

        assertThat(result).hasSize(1).containsExactly(grayInstance1);
    }

    private TrafficTagServiceInstanceListSupplier createSupplier(List<ServiceInstance> instances) {
        ServiceInstanceListSupplier delegate = new TestDelegateSupplier("user-service", instances);
        return new TrafficTagServiceInstanceListSupplier(delegate, properties);
    }

    private static class TestDelegateSupplier implements ServiceInstanceListSupplier {
        private final String serviceId;
        private final List<ServiceInstance> instances;

        TestDelegateSupplier(String serviceId, List<ServiceInstance> instances) {
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

        @Override
        public Flux<List<ServiceInstance>> get(org.springframework.cloud.client.loadbalancer.Request request) {
            return Flux.just(instances);
        }
    }
}
