package io.github.atengk.cloud.nacos.loadbalancer;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator;
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

    @Test
    @DisplayName("轮询防溢出：当 position 溢出至 Integer.MIN_VALUE 时不发生越界异常")
    void testPositionOverflowSafety() throws Exception {
        AtengNacosProperties properties = new AtengNacosProperties();
        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(new StubSupplier(Collections.emptyList())), properties);

        java.lang.reflect.Field posField = NacosWarmupLoadBalancer.class.getDeclaredField("position");
        posField.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger pos = (java.util.concurrent.atomic.AtomicInteger) posField.get(lb);
        // 设置为 MAX_VALUE - 1，下一次递增达到 MAX_VALUE，再下一次溢出为 MIN_VALUE
        pos.set(Integer.MAX_VALUE - 1);

        ServiceInstance inst1 = createInstance("inst-1", "127.0.0.1", 8080, 0, "60s", 1.0);
        ServiceInstance inst2 = createInstance("inst-2", "127.0.0.1", 8081, 0, "60s", 1.0);
        List<ServiceInstance> instances = List.of(inst1, inst2);

        for (int i = 0; i < 10; i++) {
            Response<ServiceInstance> resp = lb.processInstanceResponse(instances);
            assertThat(resp.hasServer()).isTrue();
            assertThat(resp.getServer()).isNotNull();
        }
    }

    @Test
    @DisplayName("零权重流量隔离：动态权重 0.0 的实例在混合场景下绝对不被分配流量")
    void testZeroWeightInstanceIsolatedFromTraffic() {
        AtengNacosProperties properties = new AtengNacosProperties();
        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(new StubSupplier(Collections.emptyList())), properties);

        long now = System.currentTimeMillis();
        // 节点 1：正常提供服务
        ServiceInstance active = createInstance("inst-active", "192.168.1.10", 8080, now - 100_000L, "60s", 1.0);
        // 节点 2：已被运维摘流（权重 0.0）
        ServiceInstance drained = createInstance("inst-drained", "192.168.1.20", 8080, now - 100_000L, "60s", 0.0);

        List<ServiceInstance> instances = List.of(active, drained);

        for (int i = 0; i < 50; i++) {
            Response<ServiceInstance> resp = lb.processInstanceResponse(instances);
            assertThat(resp.hasServer()).isTrue();
            // 摘流节点绝对不可被选中
            assertThat(resp.getServer().getInstanceId()).isEqualTo("inst-active");
        }
    }

    @Test
    @DisplayName("全节点摘流保底：所有节点均为 0.0 时平滑兜底均匀轮询")
    void testAllZeroWeightFallback() {
        AtengNacosProperties properties = new AtengNacosProperties();
        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(new StubSupplier(Collections.emptyList())), properties);

        long now = System.currentTimeMillis();
        ServiceInstance drained1 = createInstance("inst-drained-1", "192.168.1.10", 8080, now - 100_000L, "60s", 0.0);
        ServiceInstance drained2 = createInstance("inst-drained-2", "192.168.1.20", 8080, now - 100_000L, "60s", 0.0);

        List<ServiceInstance> instances = List.of(drained1, drained2);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            Response<ServiceInstance> resp = lb.processInstanceResponse(instances);
            assertThat(resp.hasServer()).isTrue();
            counts.put(resp.getServer().getInstanceId(), counts.getOrDefault(resp.getServer().getInstanceId(), 0) + 1);
        }
        assertThat(counts.get("inst-drained-1")).isEqualTo(10);
        assertThat(counts.get("inst-drained-2")).isEqualTo(10);
    }

    @Test
    @DisplayName("自定义 InstanceWeightCalculator SPI 注入与权重调谐测试")
    void testCustomInstanceWeightCalculatorIntegration() {
        AtengNacosProperties properties = new AtengNacosProperties();
        InstanceWeightCalculator customCalculator = (inst, w) -> "inst-vip".equals(inst.getInstanceId()) ? w * 3.0 : w;

        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(new StubSupplier(Collections.emptyList())),
                properties,
                new SimpleObjectProvider<>(customCalculator));

        ServiceInstance normalInst = createInstance("inst-normal", "192.168.1.10", 8080, 0, "60s", 1.0);
        ServiceInstance vipInst = createInstance("inst-vip", "192.168.1.20", 8080, 0, "60s", 1.0);

        assertThat(lb.calculateEffectiveWeight(normalInst, System.currentTimeMillis(), 0.10)).isEqualTo(1.0);
        assertThat(lb.calculateEffectiveWeight(vipInst, System.currentTimeMillis(), 0.10)).isEqualTo(3.0);
    }

    @Test
    @DisplayName("自适应动态资源加权：高负载节点被显著降权，健康节点获得更多调度")
    void testAdaptiveWeightCalculationReducesOverloadedSelection() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getLoadbalancer().getAdaptiveWeight().setEnabled(true);
        AdaptiveInstanceWeightCalculator adaptiveCalculator = new AdaptiveInstanceWeightCalculator(properties);

        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(new StubSupplier(Collections.emptyList())),
                properties,
                new SimpleObjectProvider<>(adaptiveCalculator));

        // 节点 1：健康节点 (CPU 40%，无降权，权重 1.0)
        ServiceInstance instHealthy = createInstanceWithLoad("inst-healthy", "192.168.1.10", 8080, "40%");
        // 节点 2：重度过载节点 (CPU 95%，严重降权至约 0.23)
        ServiceInstance instOverloaded = createInstanceWithLoad("inst-overloaded", "192.168.1.20", 8080, "95%");

        List<ServiceInstance> instances = List.of(instHealthy, instOverloaded);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            Response<ServiceInstance> resp = lb.processInstanceResponse(instances);
            assertThat(resp.hasServer()).isTrue();
            counts.put(resp.getServer().getInstanceId(), counts.getOrDefault(resp.getServer().getInstanceId(), 0) + 1);
        }

        // 健康节点权重远高于过载节点 (约 1.0 : 0.23，比例约 4:1)
        assertThat(counts.get("inst-healthy")).isGreaterThan(counts.get("inst-overloaded") * 2);
    }

    @Test
    @DisplayName("全集群过载熔断防线：全体候选节点均严重过载时安全回退均匀轮询")
    void testAllOverloadedInstancesFallbackToUniformRoundRobin() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getLoadbalancer().getAdaptiveWeight().setEnabled(true);
        AdaptiveInstanceWeightCalculator adaptiveCalculator = new AdaptiveInstanceWeightCalculator(properties);

        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(new StubSupplier(Collections.emptyList())),
                properties,
                new SimpleObjectProvider<>(adaptiveCalculator));

        // 两个节点均处于严重过载状态 (> 85%)
        ServiceInstance instOverloaded1 = createInstanceWithLoad("inst-hot-1", "192.168.1.10", 8080, "92%");
        ServiceInstance instOverloaded2 = createInstanceWithLoad("inst-hot-2", "192.168.1.20", 8080, "96%");

        List<ServiceInstance> instances = List.of(instOverloaded1, instOverloaded2);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            Response<ServiceInstance> resp = lb.processInstanceResponse(instances);
            assertThat(resp.hasServer()).isTrue();
            counts.put(resp.getServer().getInstanceId(), counts.getOrDefault(resp.getServer().getInstanceId(), 0) + 1);
        }

        // 触发全集群过载熔断，严格各均分 10 次
        assertThat(counts.get("inst-hot-1")).isEqualTo(10);
        assertThat(counts.get("inst-hot-2")).isEqualTo(10);
    }

    @Test
    @DisplayName("预热关闭时跳过启动衰减，但自适应 SPI 仍正常介入调谐")
    void testWarmupDisabledPreservesFullWeightWithSpiCalculator() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getLoadbalancer().setWarmup(false); // 关闭预热

        InstanceWeightCalculator customCalculator = (inst, w) -> w * 2.0;

        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(new StubSupplier(Collections.emptyList())),
                properties,
                new SimpleObjectProvider<>(customCalculator));

        long now = System.currentTimeMillis();
        // 刚启动 1 秒的实例
        ServiceInstance justStarted = createInstance("inst-new", "192.168.1.10", 8080, now - 1000L, "60s", 1.0);

        // 如果预热开启，此时权重会被大幅折算；预热关闭时应保留 1.0，并经 SPI 翻倍为 2.0
        double effectiveWeight = lb.calculateEffectiveWeight(justStarted, now, 0.10);
        assertThat(effectiveWeight).isEqualTo(2.0);
    }

    @Test
    @DisplayName("自定义 SPI 实现 isInstanceOverloaded 亦能无缝触发全集群过载熔断兜底")
    void testCustomSpiOverloadTriggersCircuitBreaker() {
        AtengNacosProperties properties = new AtengNacosProperties();
        InstanceWeightCalculator customOverloadCalculator = new InstanceWeightCalculator() {
            @Override
            public double calculateWeight(ServiceInstance instance, double currentWeight) {
                return currentWeight * 0.1;
            }

            @Override
            public boolean isInstanceOverloaded(ServiceInstance instance) {
                // 自定义逻辑：只要带有 overloaded=true 标记即判定为过载
                return "true".equals(instance.getMetadata().get("overloaded"));
            }
        };

        NacosWarmupLoadBalancer lb = new NacosWarmupLoadBalancer("order-service",
                new SimpleObjectProvider<>(new StubSupplier(Collections.emptyList())),
                properties,
                new SimpleObjectProvider<>(customOverloadCalculator));

        ServiceInstance inst1 = new DefaultServiceInstance("hot-1", "order-service", "192.168.1.10", 8080, false, Map.of("overloaded", "true", "nacos.weight", "1.0"));
        ServiceInstance inst2 = new DefaultServiceInstance("hot-2", "order-service", "192.168.1.20", 8080, false, Map.of("overloaded", "true", "nacos.weight", "1.0"));

        List<ServiceInstance> instances = List.of(inst1, inst2);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            Response<ServiceInstance> resp = lb.processInstanceResponse(instances);
            assertThat(resp.hasServer()).isTrue();
            counts.put(resp.getServer().getInstanceId(), counts.getOrDefault(resp.getServer().getInstanceId(), 0) + 1);
        }

        // 自定义 SPI 过载判定生效，安全回退均匀轮询
        assertThat(counts.get("hot-1")).isEqualTo(10);
        assertThat(counts.get("hot-2")).isEqualTo(10);
    }

    private ServiceInstance createInstanceWithLoad(String id, String host, int port, String cpuUsage) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("cpu-usage", cpuUsage);
        metadata.put("nacos.weight", "1.0");
        return new DefaultServiceInstance(id, "order-service", host, port, false, metadata);
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
