package io.github.atengk.cloud.nacos.loadbalancer;

import io.github.atengk.cloud.nacos.metadata.DefaultNacosMetadataProvider;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
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

    public NacosWarmupLoadBalancer(String serviceId,
                                  ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider,
                                  AtengNacosProperties properties) {
        this.serviceId = serviceId;
        this.serviceInstanceListSupplierProvider = serviceInstanceListSupplierProvider;
        this.properties = properties;
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

        boolean warmupEnabled = properties != null && properties.getLoadbalancer() != null
                && properties.getLoadbalancer().isWarmup();
        if (!warmupEnabled) {
            return selectRoundRobin(instances);
        }

        return selectWarmupWeighted(instances);
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
            if (scaled < 1) {
                scaled = 1;
            }
            weights[i] = scaled;
            totalWeight += scaled;
            if (i > 0 && weights[i] != weights[i - 1]) {
                allEqual = false;
            }
        }

        // 若所有实例权重均相同（例如均已预热完毕），直接走极速均匀轮询
        if (allEqual || totalWeight <= 0) {
            return selectRoundRobin(instances);
        }

        // 采用 Nginx Smooth Weighted Round-Robin 算法
        int maxCurrentWeight = Integer.MIN_VALUE;
        ServiceInstance selected = instances.get(0);
        String selectedKey = null;

        for (int i = 0; i < instances.size(); i++) {
            ServiceInstance inst = instances.get(i);
            String key = buildInstanceKey(inst);
            int weight = weights[i];

            AtomicInteger cur = currentWeights.computeIfAbsent(key, k -> new AtomicInteger(0));
            int newCurrent = cur.addAndGet(weight);
            if (newCurrent > maxCurrentWeight) {
                maxCurrentWeight = newCurrent;
                selected = inst;
                selectedKey = key;
            }
        }

        if (selectedKey != null) {
            AtomicInteger selectedCur = currentWeights.get(selectedKey);
            if (selectedCur != null) {
                selectedCur.addAndGet(-totalWeight);
            }
        }

        // 定期清理已下线实例的权重状态，防范内存泄露
        if (currentWeights.size() > 100) {
            cleanStaleWeights(instances);
        }

        return new DefaultResponse(selected);
    }

    /**
     * 标准均匀轮询
     */
    private Response<ServiceInstance> selectRoundRobin(List<ServiceInstance> instances) {
        int pos = Math.abs(position.incrementAndGet());
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
            } catch (NumberFormatException ignored) {
            }
        }

        String startupTimeStr = meta.get(DefaultNacosMetadataProvider.KEY_STARTUP_TIME);
        if (!StringUtils.hasText(startupTimeStr)) {
            return baseWeight;
        }

        long startupTime;
        try {
            startupTime = Long.parseLong(startupTimeStr);
        } catch (NumberFormatException e) {
            return baseWeight;
        }

        long uptime = Math.max(0, now - startupTime);
        long warmupDurationMs = parseWarmupDuration(meta.get(DefaultNacosMetadataProvider.KEY_WARMUP_DURATION));

        if (warmupDurationMs <= 0 || uptime >= warmupDurationMs) {
            return baseWeight;
        }

        // 线性升权算法：floor + (1.0 - floor) * (uptime / warmupDuration)
        double progress = (double) uptime / (double) warmupDurationMs;
        double weightRatio = floorRatio + (1.0 - floorRatio) * progress;
        weightRatio = Math.min(1.0, Math.max(floorRatio, weightRatio));

        return baseWeight * weightRatio;
    }

    private long parseWarmupDuration(String warmupDurationStr) {
        if (!StringUtils.hasText(warmupDurationStr)) {
            return 60_000L;
        }
        String s = warmupDurationStr.trim().toLowerCase();
        if (s.endsWith("s")) {
            s = s.substring(0, s.length() - 1);
        }
        try {
            long seconds = Long.parseLong(s);
            return seconds > 0 ? seconds * 1000L : 60_000L;
        } catch (NumberFormatException e) {
            return 60_000L;
        }
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
