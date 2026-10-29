package io.github.atengk.cloud.traffic.loadbalancer;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.exception.TrafficRoutingException;
import io.github.atengk.cloud.traffic.interceptor.TrafficClientHttpRequestInterceptor;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨跳泳道穿透保持 (Lane Continuity) 与平滑降级全链路多跳模拟单元测试
 *
 * <p>模拟调用链路：
 * 请求 (tag=v2) ➔ Service A (灰度) ➔ Service B (未发版，仅有基线实例) ➔ Service C (同时有基线与 v2 实例)
 * 验证：
 * 1. Service A 呼叫 Service B 时，因 Service B 缺少 v2 实例而平滑降级调度至基线实例 (零 503 报警)；
 * 2. Service B 处理过程中保留了原始流量标签，并在出站请求中自动携带；
 * 3. Service B 呼叫 Service C 时，重新精准命中 Service C 的 v2 灰度节点 (泳道回升成功)；
 * 4. 在严格隔离模式下，未命中时准确阻断抛出 TrafficRoutingException。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
class LaneContinuityAndFallbackTest {

    private AtengTrafficProperties properties;
    private TrafficClientHttpRequestInterceptor outboundInterceptor;

    @BeforeEach
    void setUp() {
        properties = new AtengTrafficProperties();
        outboundInterceptor = new TrafficClientHttpRequestInterceptor(properties);
        TrafficContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TrafficContextHolder.clear();
    }

    @Test
    @DisplayName("测试跨跳泳道穿透：A(gray) ➔ B(fallback baseline) ➔ C(re-enter gray)")
    void testCrossHopLaneContinuity() throws IOException {
        // 1. 请求携带流量标签 v2 进入系统
        TrafficContextHolder.setTag("v2");
        assertThat(TrafficContextHolder.getTag()).isEqualTo("v2");

        // 2. Service A 准备调用 Service B
        // Service B 只有基线实例 (未发版)
        ServiceInstance serviceB_base1 = new DefaultServiceInstance("b-inst-1", "service-b", "10.0.1.1", 8080, false, Collections.emptyMap());
        ServiceInstance serviceB_base2 = new DefaultServiceInstance("b-inst-2", "service-b", "10.0.1.2", 8080, false, Map.of("tag", "default"));
        TrafficTagServiceInstanceListSupplier supplierForB = new TrafficTagServiceInstanceListSupplier(
                new StaticServiceSupplier("service-b", List.of(serviceB_base1, serviceB_base2)),
                properties
        );

        // 调用 Service B，触发平滑降级回退至基线
        List<ServiceInstance> chosenForB = supplierForB.filterByTag(List.of(serviceB_base1, serviceB_base2), TrafficContextHolder.getTag());
        assertThat(chosenForB).hasSize(2).containsExactlyInAnyOrder(serviceB_base1, serviceB_base2);

        // 验证：虽然 B 降级到了基线，但当前请求上下文依然完好保留着原始标签 v2 (Lane Continuity)
        assertThat(TrafficContextHolder.getTag()).isEqualTo("v2");

        // 3. Service B 执行业务逻辑并准备出站调用 Service C
        MockClientHttpRequest requestToC = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://service-c/api/info"));
        outboundInterceptor.intercept(requestToC, new byte[0], (req, body) -> null);
        assertThat(requestToC.getHeaders().getFirst(TrafficConstants.HEADER_TRAFFIC_TAG)).isEqualTo("v2");

        // 4. Service B 侧的 LoadBalancer 挑选 Service C 实例
        // Service C 部署了基线实例与 v2 灰度实例
        ServiceInstance serviceC_base = new DefaultServiceInstance("c-inst-base", "service-c", "10.0.2.1", 8080, false, Collections.emptyMap());
        ServiceInstance serviceC_v2 = new DefaultServiceInstance("c-inst-v2", "service-c", "10.0.2.2", 8080, false, Map.of("tag", "v2"));
        TrafficTagServiceInstanceListSupplier supplierForC = new TrafficTagServiceInstanceListSupplier(
                new StaticServiceSupplier("service-c", List.of(serviceC_base, serviceC_v2)),
                properties
        );

        // 调用 Service C：重新回升至 v2 灰度节点！
        List<ServiceInstance> chosenForC = supplierForC.filterByTag(List.of(serviceC_base, serviceC_v2), TrafficContextHolder.getTag());
        assertThat(chosenForC).hasSize(1).containsExactly(serviceC_v2);
    }

    @Test
    @DisplayName("测试在 strictLane=true 金融模式下，B 缺少对应标签直接抛出 TrafficRoutingException 阻断")
    void testStrictLaneFastFailOnMissingTag() {
        TrafficContextHolder.setTag("v2");
        properties.setStrictLane(true);

        ServiceInstance serviceB_base1 = new DefaultServiceInstance("b-inst-1", "service-b", "10.0.1.1", 8080, false, Collections.emptyMap());
        TrafficTagServiceInstanceListSupplier supplierForB = new TrafficTagServiceInstanceListSupplier(
                new StaticServiceSupplier("service-b", List.of(serviceB_base1)),
                properties
        );

        assertThatThrownBy(() -> supplierForB.filterByTag(List.of(serviceB_base1), TrafficContextHolder.getTag()))
                .isInstanceOf(TrafficRoutingException.class)
                .hasMessageContaining("严格泳道隔离阻断")
                .hasMessageContaining("service-b")
                .hasMessageContaining("v2");
    }

    private static class StaticServiceSupplier implements ServiceInstanceListSupplier {
        private final String serviceId;
        private final List<ServiceInstance> instances;

        StaticServiceSupplier(String serviceId, List<ServiceInstance> instances) {
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
