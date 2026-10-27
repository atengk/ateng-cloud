package io.github.atengk.cloud.nacos.loadbalancer;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.SimpleObjectProvider;
import reactor.core.publisher.Flux;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link NacosWarmupLoadBalancer} 单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
class NacosWarmupLoadBalancerTest {

    @Test
    @DisplayName("预热动态权重计算：线性爬坡与 10% 底噪保底")
    void testCalculateEffectiveWeight() {
        AtengNacosProperties properties = new AtengNacosProperties();
        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(new StubSupplier(Collections.emptyList())), properties);

        long now = 10000000L;
        double floorRatio = 0.10;

        // 1. 无元数据或无 startup-time，返回默认权重 1.0
        ServiceInstance instNoMeta = new DefaultServiceInstance("inst-1", "order-service", "127.0.0.1", 8080, false);
        assertThat(lb.calculateEffectiveWeight(instNoMeta, now, floorRatio)).isEqualTo(1.0);

        // 2. 刚启动（uptime = 0），获得 10% 底噪保底权重
        ServiceInstance instCold = createInstance("inst-cold", "127.0.0.1", 8081, now, "60s", 1.0);
        assertThat(lb.calculateEffectiveWeight(instCold, now, floorRatio)).isCloseTo(0.10, within(0.001));

        // 3. 运行 30s（预热进度 50%）：0.10 + (1.0 - 0.10) * 0.5 = 0.55
        ServiceInstance instMid = createInstance("inst-mid", "127.0.0.1", 8082, now - 30_000L, "60s", 1.0);
        assertThat(lb.calculateEffectiveWeight(instMid, now, floorRatio)).isCloseTo(0.55, within(0.001));

        // 4. 运行 60s（预热完毕）：恢复 100% 原始权重 1.0
        ServiceInstance instWarm = createInstance("inst-warm", "127.0.0.1", 8083, now - 60_000L, "60s", 1.0);
        assertThat(lb.calculateEffectiveWeight(instWarm, now, floorRatio)).isEqualTo(1.0);

        // 5. 运行 120s（超出预热期）：保持 100% 原始权重 1.0
        ServiceInstance instOld = createInstance("inst-old", "127.0.0.1", 8084, now - 120_000L, "60s", 1.0);
        assertThat(lb.calculateEffectiveWeight(instOld, now, floorRatio)).isEqualTo(1.0);

        // 6. 自定义预热时长 30s，运行 15s 达到 50%
        ServiceInstance instCustom = createInstance("inst-cust", "127.0.0.1", 8085, now - 15_000L, "30s", 1.0);
        assertThat(lb.calculateEffectiveWeight(instCustom, now, floorRatio)).isCloseTo(0.55, within(0.001));

        // 7. 基础权重为 0.5，预热中线性等比缩放
        ServiceInstance instBaseHalf = createInstance("inst-half", "127.0.0.1", 8086, now - 30_000L, "60s", 0.5);
        assertThat(lb.calculateEffectiveWeight(instBaseHalf, now, floorRatio)).isCloseTo(0.5 * 0.55, within(0.001));
    }

    @Test
    @DisplayName("边界防御：空实例列表返回 EmptyResponse，单实例直接返回")
    void testEmptyAndSingleInstance() {
        AtengNacosProperties properties = new AtengNacosProperties();
        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(new StubSupplier(Collections.emptyList())), properties);

        Response<ServiceInstance> emptyResp = lb.processInstanceResponse(null);
        assertThat(emptyResp).isInstanceOf(EmptyResponse.class);

        emptyResp = lb.processInstanceResponse(Collections.emptyList());
        assertThat(emptyResp).isInstanceOf(EmptyResponse.class);

        ServiceInstance singleInst = createInstance("inst-1", "127.0.0.1", 8080, 0, "60s", 1.0);
        Response<ServiceInstance> singleResp = lb.processInstanceResponse(List.of(singleInst));
        assertThat(singleResp.hasServer()).isTrue();
        assertThat(singleResp.getServer().getInstanceId()).isEqualTo("inst-1");
    }

    @Test
    @DisplayName("平滑加权轮询调度：新老节点混合时按动态权重平滑交替选择")
    void testSmoothWeightedRoundRobinSelection() {
        AtengNacosProperties properties = new AtengNacosProperties();
        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(new StubSupplier(Collections.emptyList())), properties);

        long now = System.currentTimeMillis();
        // 节点 1：老节点，完全预热完毕，权重 100
        ServiceInstance instWarm = createInstance("inst-warm", "192.168.1.10", 8080, now - 100_000L, "60s", 1.0);
        // 节点 2：刚启动新节点，uptime=0，底噪权重 10 (floorRatio=0.10)
        ServiceInstance instCold = createInstance("inst-cold", "192.168.1.20", 8080, now, "60s", 1.0);

        List<ServiceInstance> instances = List.of(instWarm, instCold);

        // 权重比为 100 : 10 -> 10 : 1，在连续 11 次调度中，老节点应被选中 10 次，新节点应被选中 1 次
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 11; i++) {
            Response<ServiceInstance> response = lb.processInstanceResponse(instances);
            assertThat(response.hasServer()).isTrue();
            String id = response.getServer().getInstanceId();
            counts.put(id, counts.getOrDefault(id, 0) + 1);
        }

        assertThat(counts.get("inst-warm")).isEqualTo(10);
        assertThat(counts.get("inst-cold")).isEqualTo(1);
    }

    @Test
    @DisplayName("预热全完成快速路径：当所有节点均已预热完成时退化为标准均匀轮询")
    void testAllWarmInstancesRoundRobin() {
        AtengNacosProperties properties = new AtengNacosProperties();
        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(new StubSupplier(Collections.emptyList())), properties);

        long now = System.currentTimeMillis();
        ServiceInstance inst1 = createInstance("inst-1", "192.168.1.10", 8080, now - 100_000L, "60s", 1.0);
        ServiceInstance inst2 = createInstance("inst-2", "192.168.1.20", 8080, now - 100_000L, "60s", 1.0);

        List<ServiceInstance> instances = List.of(inst1, inst2);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            Response<ServiceInstance> response = lb.processInstanceResponse(instances);
            String id = response.getServer().getInstanceId();
            counts.put(id, counts.getOrDefault(id, 0) + 1);
        }

        assertThat(counts.get("inst-1")).isEqualTo(50);
        assertThat(counts.get("inst-2")).isEqualTo(50);
    }

    @Test
    @DisplayName("响应式选路：通过 choose 接口响应 Mono")
    void testChooseReactive() {
        AtengNacosProperties properties = new AtengNacosProperties();
        ServiceInstance inst1 = createInstance("inst-1", "192.168.1.10", 8080, 0, "60s", 1.0);
        StubSupplier supplier = new StubSupplier(List.of(inst1));

        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(supplier), properties);

        Response<ServiceInstance> response = lb.choose().block();
        assertThat(response).isNotNull();
        assertThat(response.hasServer()).isTrue();
        assertThat(response.getServer().getInstanceId()).isEqualTo("inst-1");
    }

    private ServiceInstance createInstance(String id, String host, int port, long startupTime, String warmupDuration, double baseWeight) {
        Map<String, String> metadata = new HashMap<>();
        if (startupTime > 0) {
            metadata.put("startup-time", String.valueOf(startupTime));
        }
        if (warmupDuration != null) {
            metadata.put("warmup-duration", warmupDuration);
        }
        metadata.put("nacos.weight", String.valueOf(baseWeight));
        return new DefaultServiceInstance(id, "order-service", host, port, false, metadata);
    }

    private static class StubSupplier implements ServiceInstanceListSupplier {
        private final List<ServiceInstance> instances;

        StubSupplier(List<ServiceInstance> instances) {
            this.instances = instances;
        }

        @Override
        public String getServiceId() {
            return "order-service";
        }

        @Override
        public Flux<List<ServiceInstance>> get() {
            return Flux.just(instances);
        }
    }
}
