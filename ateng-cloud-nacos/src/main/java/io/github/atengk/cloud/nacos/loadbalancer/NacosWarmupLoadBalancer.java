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
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nacos 平滑启动预热 (Startup Preheating) 加权轮询负载均衡选择器
 *
 * <p>核心治理语义：
 * 1. 消费实例元数据中的 {@code startup-time} 与 {@code warmup-duration}（默认 60s）；
 * 2. 预热期内按线性升权公式 {@code floor + (1.0 - floor) * (uptime / warmupDuration)} 动态计算权重（保底 10% 底噪）；
 * 3. 采用平滑加权轮询算法（Smooth Weighted Round-Robin）无抖动调度流量；
 * 4. 预热完成后平滑收敛为均匀轮询，零额外计算开销。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class NacosWarmupLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(NacosWarmupLoadBalancer.class);

    private final String serviceId;
    private final ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider;
    private final AtengNacosProperties properties;
    private final AtomicInteger position = new AtomicInteger(new Random().nextInt(1000));
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

        boolean warmupEnabled = properties != null && properties.getLoadbalancer() != null
                && properties.getLoadbalancer().isWarmup();
        boolean adaptiveEnabled = properties != null && properties.getLoadbalancer() != null
                && properties.getLoadbalancer().getAdaptiveWeight() != null
                && properties.getLoadbalancer().getAdaptiveWeight().isEnabled();
        boolean hasCustomCalculator = weightCalculatorProvider != null && weightCalculatorProvider.getIfAvailable() != null;

        if (!warmupEnabled && !adaptiveEnabled && !hasCustomCalculator) {
            return selectRoundRobin(instances);
        }

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
        double floorRatio = (properties != null && properties.getLoadbalancer() != null)
                ? properties.getLoadbalancer().getWarmupFloorWeightRatio() : 0.10;

        int[] weights = new int[instances.size()];
        boolean allEqual = true;
        int totalWeight = 0;

        for (int i = 0; i < instances.size(); i++) {
            ServiceInstance inst = instances.get(i);
            double effectiveWeight = calculateEffectiveWeight(inst, now, floorRatio);
            int scaled = (int) Math.round(effectiveWeight * 100.0);
            if (effectiveWeight <= 0.0 || scaled < 0) {
                scaled = 0;
            } else if (scaled < 1) {
                scaled = 1;
            }
            weights[i] = scaled;
            totalWeight += scaled;
            if (i > 0 && weights[i] != weights[i - 1]) {
                allEqual = false;
            }
        }

        // 若所有有效权重均相同且大于 0（例如均已预热完毕），直接走极速均匀轮询
        if (allEqual && totalWeight > 0) {
            return selectRoundRobin(instances);
        }

        // 若所有节点权重均为 0（全部被运维隔离/摘流），兜底走均匀轮询避免请求无响应
        if (totalWeight <= 0) {
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
                selectedCur.addAndGet(-totalWeight);
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
     * 计算实例的实时动态有效权重（0.0 ~ 1.0）
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
        String baseWeightStr = meta.get("nacos.weight");
        if (StringUtils.hasText(baseWeightStr)) {
            try {
                baseWeight = Double.parseDouble(baseWeightStr);
                if (baseWeight <= 0.0) {
                    return 0.0;
                }
            } catch (NumberFormatException e) {
                log.debug("Failed to parse nacos.weight '{}' for instance {}: {}", baseWeightStr, instance.getInstanceId(), e.getMessage());
            }
        }

        double weight = baseWeight;
        boolean warmupEnabled = properties == null || properties.getLoadbalancer() == null
                || properties.getLoadbalancer().isWarmup();
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

        return Math.max(0.0, weight);
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
