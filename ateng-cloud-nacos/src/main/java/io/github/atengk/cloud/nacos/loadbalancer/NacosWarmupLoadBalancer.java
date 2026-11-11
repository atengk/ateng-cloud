package io.github.atengk.cloud.nacos.loadbalancer;

import io.github.atengk.cloud.nacos.metadata.DefaultNacosMetadataProvider;
import io.github.atengk.cloud.nacos.metadata.NacosWarmupHelper;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.NoopServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nacos 静态权重、启动预热与自适应负载平滑加权轮询选择器
 *
 * <p>核心治理语义：
 * 1. <b>静态基准权重常驻</b>：无论是否开启预热或自适应调谐，始终解析实例元数据中的 {@code nacos.weight}；
 * 2. <b>启动预热线性升权</b>：开启预热时按 {@code baseWeight * (floor + (1.0 - floor) * (uptime / warmupDuration))} 计算动态权重；
 * 3. <b>自适应负载调谐</b>：按需叠加 {@link InstanceWeightCalculator} SPI，全集群过载时触发熔断回退均匀轮询；
 * 4. <b>双模极速路径</b>：当所有候选实例有效权重完全相等且大于 0 时退化为无锁均匀轮询，否则执行 Nginx 平滑加权轮询（SWRR）。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class NacosWarmupLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(NacosWarmupLoadBalancer.class);

    /**
     * Nacos 实例元数据静态基准权重键名
     */
    public static final String METADATA_KEY_WEIGHT = "nacos.weight";

    private final String serviceId;
    private final ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider;
    private final AtengNacosProperties properties;
    private final AtomicInteger position = new AtomicInteger(ThreadLocalRandom.current().nextInt(1000));
    private final ConcurrentMap<String, AtomicInteger> currentWeights = new ConcurrentHashMap<>();

    private final ObjectProvider<InstanceWeightCalculator> weightCalculatorProvider;

    public NacosWarmupLoadBalancer(String serviceId,
                                  ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider,
                                  AtengNacosProperties properties) {
        this(serviceId, serviceInstanceListSupplierProvider, properties, null);
    }

    public NacosWarmupLoadBalancer(String serviceId,
                                  ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider,
                                  AtengNacosProperties properties,
                                  @Nullable ObjectProvider<InstanceWeightCalculator> weightCalculatorProvider) {
        this.serviceId = serviceId;
        this.serviceInstanceListSupplierProvider = serviceInstanceListSupplierProvider;
        this.properties = properties;
        this.weightCalculatorProvider = weightCalculatorProvider;
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = serviceInstanceListSupplierProvider
                .getIfAvailable(NoopServiceInstanceListSupplier::new);
        return supplier.get(request).next()
                .map(this::processInstanceResponse);
    }

    /**
     * 根据候选实例列表执行负载均衡选取
     */
    public Response<ServiceInstance> processInstanceResponse(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            log.warn("[ateng-cloud-nacos] No service instances available for service '{}'", serviceId);
            return new EmptyResponse();
        }

        if (instances.size() == 1) {
            return new DefaultResponse(instances.get(0));
        }

        // 全集群过载熔断保护：若全体候选节点均处于严重过载状态，安全回退均匀轮询
        if (isAllInstancesOverloaded(instances)) {
            log.warn("[ateng-cloud-nacos] All candidate service instances for '{}' are severely overloaded! Triggering global overload circuit protection, falling back to uniform round-robin.", serviceId);
            return selectRoundRobin(instances);
        }

        // 统一进入有效权重解析（常驻尊重 nacos.weight 静态权重；权重全相等且 > 0 时内部自动走均匀轮询极速路径）
        return selectWarmupWeighted(instances);
    }

    /**
     * 判定是否所有候选实例均处于严重过载状态
     */
    private boolean isAllInstancesOverloaded(List<ServiceInstance> instances) {
        if (instances == null || instances.size() <= 1) {
            return false;
        }
        if (weightCalculatorProvider == null) {
            return false;
        }
        InstanceWeightCalculator calculator = weightCalculatorProvider.getIfAvailable();
        if (calculator != null) {
            return instances.stream().allMatch(calculator::isInstanceOverloaded);
        }
        return false;
    }

    /**
     * 平滑加权轮询选择
     */
    private Response<ServiceInstance> selectWarmupWeighted(List<ServiceInstance> instances) {
        long now = System.currentTimeMillis();
        double floorRatio = (properties != null) ? properties.getWarmupFloorWeightRatio() : 0.10;

        int[] weights = new int[instances.size()];
        boolean allEqual = true;
        long totalWeight = 0L;

        for (int i = 0; i < instances.size(); i++) {
            ServiceInstance inst = instances.get(i);
            double effectiveWeight = calculateEffectiveWeight(inst, now, floorRatio);
            int scaled;
            if (!Double.isFinite(effectiveWeight) || effectiveWeight <= 0.0) {
                scaled = 0;
            } else {
                long rounded = Math.round(effectiveWeight * 100.0);
                scaled = rounded < 1L ? 1 : (int) Math.min(1_000_000L, rounded);
            }
            weights[i] = scaled;
            totalWeight += scaled;
            if (i > 0 && weights[i] != weights[i - 1]) {
                allEqual = false;
            }
        }

        // 若所有有效权重均相同且大于 0（例如均已预热完毕），直接走极速均匀轮询
        if (allEqual && totalWeight > 0L) {
            return selectRoundRobin(instances);
        }

        // 若所有节点权重均为 0（全部被运维隔离/摘流），兜底走均匀轮询避免请求无响应
        if (totalWeight <= 0L) {
            return selectRoundRobin(instances);
        }

        // 采用 Nginx Smooth Weighted Round-Robin 算法
        int maxCurrentWeight = Integer.MIN_VALUE;
        ServiceInstance selected = null;
        String selectedKey = null;

        for (int i = 0; i < instances.size(); i++) {
            int weight = weights[i];
            if (weight <= 0) {
                continue; // 彻底跳过摘流/隔离节点，绝不分配流量
            }
            ServiceInstance inst = instances.get(i);
            String key = buildInstanceKey(inst);

            AtomicInteger cur = currentWeights.computeIfAbsent(key, k -> new AtomicInteger(0));
            int newCurrent = cur.addAndGet(weight);
            if (newCurrent > maxCurrentWeight) {
                maxCurrentWeight = newCurrent;
                selected = inst;
                selectedKey = key;
            }
        }

        if (selected == null) {
            return selectRoundRobin(instances);
        }

        if (selectedKey != null) {
            AtomicInteger selectedCur = currentWeights.get(selectedKey);
            if (selectedCur != null) {
                int deduction = (int) Math.min(Integer.MAX_VALUE, totalWeight);
                selectedCur.addAndGet(-deduction);
            }
        }

        // 定期清理已下线实例的权重状态，自适应防范容器频繁缩容/轮换导致的内存泄露
        if (currentWeights.size() > Math.max(16, instances.size() * 2)) {
            cleanStaleWeights(instances);
        }

        return new DefaultResponse(selected);
    }

    /**
     * 标准均匀轮询
     */
    private Response<ServiceInstance> selectRoundRobin(List<ServiceInstance> instances) {
        int pos = position.incrementAndGet() & Integer.MAX_VALUE;
        ServiceInstance instance = instances.get(pos % instances.size());
        return new DefaultResponse(instance);
    }

    /**
     * 计算实例的实时动态有效权重（非负数值，支持静态基准权重与 SPI 自适应调谐）
     */
    public double calculateEffectiveWeight(ServiceInstance instance, long now, double floorRatio) {
        if (instance == null) {
            return 1.0;
        }

        Map<String, String> meta = instance.getMetadata();
        if (meta == null) {
            return 1.0;
        }

        // 检查基础权重
        double baseWeight = 1.0;
        String baseWeightStr = meta.get(METADATA_KEY_WEIGHT);
        if (StringUtils.hasText(baseWeightStr)) {
            try {
                double parsed = Double.parseDouble(baseWeightStr);
                if (!Double.isFinite(parsed) || parsed <= 0.0) {
                    return 0.0;
                }
                baseWeight = parsed;
            } catch (NumberFormatException e) {
                log.debug("Failed to parse nacos.weight '{}' for instance {}: {}", baseWeightStr, instance.getInstanceId(), e.getMessage());
            }
        }

        double weight = baseWeight;
        boolean warmupEnabled = properties == null || properties.isLoadBalancerWarmupEnabled();
        if (warmupEnabled) {
            String startupTimeStr = meta.get(DefaultNacosMetadataProvider.KEY_STARTUP_TIME);
            if (StringUtils.hasText(startupTimeStr)) {
                try {
                    long startupTime = Long.parseLong(startupTimeStr);
                    long uptime = Math.max(0, now - startupTime);
                    long warmupDurationMs = NacosWarmupHelper.parseDurationToSeconds(
                            meta.get(DefaultNacosMetadataProvider.KEY_WARMUP_DURATION), 60L) * 1000L;

                    if (warmupDurationMs > 0 && uptime < warmupDurationMs) {
                        // 线性升权算法：floor + (1.0 - floor) * (uptime / warmupDuration)
                        double progress = (double) uptime / (double) warmupDurationMs;
                        double weightRatio = floorRatio + (1.0 - floorRatio) * progress;
                        weightRatio = Math.min(1.0, Math.max(floorRatio, weightRatio));
                        weight = baseWeight * weightRatio;
                    }
                } catch (NumberFormatException e) {
                    log.debug("Failed to parse startup-time '{}' for instance {}: {}", startupTimeStr, instance.getInstanceId(), e.getMessage());
                }
            }
        }

        // 叠加自适应动态加权计算器 SPI 调谐
        if (weightCalculatorProvider != null) {
            InstanceWeightCalculator calculator = weightCalculatorProvider.getIfAvailable();
            if (calculator != null) {
                weight = calculator.calculateWeight(instance, weight);
            }
        }

        return Double.isFinite(weight) ? Math.max(0.0, weight) : 0.0;
    }

    private String buildInstanceKey(ServiceInstance inst) {
        return inst.getHost() + ":" + inst.getPort();
    }

    private void cleanStaleWeights(List<ServiceInstance> activeInstances) {
        java.util.Set<String> activeKeys = new java.util.HashSet<>();
        for (ServiceInstance inst : activeInstances) {
            activeKeys.add(buildInstanceKey(inst));
        }
        currentWeights.keySet().removeIf(k -> !activeKeys.contains(k));
    }
}
