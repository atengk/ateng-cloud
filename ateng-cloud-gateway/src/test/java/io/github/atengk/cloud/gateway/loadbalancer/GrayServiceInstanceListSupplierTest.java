package io.github.atengk.cloud.gateway.loadbalancer;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 金丝雀灰度与平滑降级服务实例提供者单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GrayServiceInstanceListSupplierTest {

    private ServiceInstance createInstance(String id, String host, int port, String tag) {
        Map<String, String> metadata = new HashMap<>();
        if (tag != null) {
            metadata.put("tag", tag);
        }
        return new DefaultServiceInstance(id, "test-service", host, port, false, metadata);
    }

    private ServiceInstanceListSupplier createDelegate(List<ServiceInstance> instances) {
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

    @Test
    @DisplayName("测试携带灰度标签流量精准路由至对应灰度节点")
    void testFilterByTagMatch() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(true);

        ServiceInstance baseline1 = createInstance("base-1", "10.0.0.1", 8080, null);
        ServiceInstance baseline2 = createInstance("base-2", "10.0.0.2", 8080, "default");
        ServiceInstance gray1 = createInstance("gray-1", "10.0.0.3", 8080, "v2");
        ServiceInstance gray2 = createInstance("gray-2", "10.0.0.4", 8080, "v3");

        GrayServiceInstanceListSupplier supplier = new GrayServiceInstanceListSupplier(
                createDelegate(List.of(baseline1, baseline2, gray1, gray2)), props);

        List<ServiceInstance> filtered = supplier.filterByTag(List.of(baseline1, baseline2, gray1, gray2), "v2");
        assertEquals(1, filtered.size());
        assertEquals("gray-1", filtered.get(0).getInstanceId());
    }

    @Test
    @DisplayName("测试未携带灰度标签的基线流量优先路由至默认基线节点")
    void testFilterByNoTagRoutesToBaseline() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(true);

        ServiceInstance baseline1 = createInstance("base-1", "10.0.0.1", 8080, null);
        ServiceInstance baseline2 = createInstance("base-2", "10.0.0.2", 8080, "default");
        ServiceInstance gray1 = createInstance("gray-1", "10.0.0.3", 8080, "v2");

        GrayServiceInstanceListSupplier supplier = new GrayServiceInstanceListSupplier(
                createDelegate(List.of(baseline1, baseline2, gray1)), props);

        List<ServiceInstance> filtered = supplier.filterByTag(List.of(baseline1, baseline2, gray1), null);
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(i -> "base-1".equals(i.getInstanceId())));
        assertTrue(filtered.stream().anyMatch(i -> "base-2".equals(i.getInstanceId())));
    }

    @Test
    @DisplayName("测试灰度节点全部下线且 fallbackToDefault=true 时平滑降级至基线节点")
    void testFallbackToDefaultWhenGrayDown() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(true);
        props.setFallbackToDefault(true);

        ServiceInstance baseline1 = createInstance("base-1", "10.0.0.1", 8080, null);
        ServiceInstance baseline2 = createInstance("base-2", "10.0.0.2", 8080, "default");

        GrayServiceInstanceListSupplier supplier = new GrayServiceInstanceListSupplier(
                createDelegate(List.of(baseline1, baseline2)), props);

        // 请求携带 v2 灰度标签，但集群内无 v2 节点
        List<ServiceInstance> filtered = supplier.filterByTag(List.of(baseline1, baseline2), "v2");
        assertEquals(2, filtered.size(), "灰度节点缺失时应平滑降级至基线实例");
    }

    @Test
    @DisplayName("测试灰度节点下线且 fallbackToDefault=false 时阻断流量返回空列表")
    void testNoFallbackReturnsEmptyList() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(true);
        props.setFallbackToDefault(false);

        ServiceInstance baseline1 = createInstance("base-1", "10.0.0.1", 8080, null);
        GrayServiceInstanceListSupplier supplier = new GrayServiceInstanceListSupplier(
                createDelegate(List.of(baseline1)), props);

        List<ServiceInstance> filtered = supplier.filterByTag(List.of(baseline1), "v2");
        assertTrue(filtered.isEmpty(), "未开启降级时无对应灰度节点应返回空列表");
    }

    @Test
    @DisplayName("测试从 Request 上下文解析 X-Traffic-Tag 标头并执行灰度分流")
    void testGetWithRequestDataContext() {
        AtengGatewayTrafficProperties props = new AtengGatewayTrafficProperties();
        props.setEnabled(true);

        ServiceInstance baseline = createInstance("base-1", "10.0.0.1", 8080, null);
        ServiceInstance gray = createInstance("gray-1", "10.0.0.2", 8080, "canary");

        GrayServiceInstanceListSupplier supplier = new GrayServiceInstanceListSupplier(
                createDelegate(List.of(baseline, gray)), props);

        HttpHeaders headers = new HttpHeaders();
        headers.set(GatewayConstants.X_TRAFFIC_TAG, "canary");
        RequestData requestData = new RequestData(HttpMethod.GET, URI.create("/api/test"), headers, null, Collections.emptyMap());
        RequestDataContext context = new RequestDataContext(requestData);
        DefaultRequest<RequestDataContext> request = new DefaultRequest<>(context);

        List<ServiceInstance> result = supplier.get(request).blockFirst();
        assertEquals(1, result.size());
        assertEquals("gray-1", result.get(0).getInstanceId());
    }
}
