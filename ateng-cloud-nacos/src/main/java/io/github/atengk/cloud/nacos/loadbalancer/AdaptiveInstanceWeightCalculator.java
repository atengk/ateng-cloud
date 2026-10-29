package io.github.atengk.cloud.nacos.loadbalancer;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties.AdaptiveWeightProperties;
import io.github.atengk.cloud.nacos.spi.InstanceWeightCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * 基于微服务节点系统资源指标（CPU/内存负载）的自适应平滑加权计算器
 *
 * <p>核心治理语义：
 * <ul>
 *     <li><b>指标感知</b>：解析实例元数据中的 CPU（{@code cpu-usage}/{@code cpu-load}）与内存（{@code memory-usage}）负载指标；</li>
 *     <li><b>平滑降权</b>：处于健康负载时保持 100% 原始权重；处于中度/重度过载时按线性阻尼曲线平滑降权；</li>
 *     <li><b>保底底噪 (minFloorWeightRatio)</b>：即使极限满载，保底分配 10% 探活底噪流量，防止权重归零导致节点完全饥饿；</li>
 *     <li><b>阻尼防抖 (Damping)</b>：基于采样周期（默认 5s）与防抖门槛（默认 15%），消除微小负载波动引发的频繁调度震荡；</li>
 *     <li><b>无锁高并发</b>：全路径非阻塞原子计算，零 GC 负担。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class AdaptiveInstanceWeightCalculator implements InstanceWeightCalculator {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveInstanceWeightCalculator.class);

    private static final String[] CPU_KEYS = {"cpu-usage", "cpu-load", "cpu.usage", "cpu.load", "system-load"};
    private static final String[] MEMORY_KEYS = {"memory-usage", "mem-usage", "memory.usage", "mem.usage"};

    private static final int MAX_CACHE_ENTRIES = 1024;
    private static final long CACHE_EXPIRE_MS = 600_000L; // 10 分钟

    private final AtengNacosProperties properties;
    private final ConcurrentMap<String, CachedWeightRecord> instanceWeightCache = new ConcurrentHashMap<>();
    private final LongSupplier systemTimeProvider;

    public AdaptiveInstanceWeightCalculator(AtengNacosProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    public AdaptiveInstanceWeightCalculator(AtengNacosProperties properties, LongSupplier systemTimeProvider) {
        this.properties = properties;
        this.systemTimeProvider = systemTimeProvider != null ? systemTimeProvider : System::currentTimeMillis;
    }

    @Override
    public double calculateWeight(ServiceInstance instance, double currentWeight) {
        if (instance == null || currentWeight <= 0.0) {
            return 0.0;
        }

        AdaptiveWeightProperties config = getAdaptiveConfig();
        if (config == null || !config.isEnabled()) {
            return currentWeight;
        }

        Map<String, String> meta = instance.getMetadata();
        if (meta == null || meta.isEmpty()) {
            return currentWeight;
        }

        Double cpuUsage = parseMetric(meta, CPU_KEYS);
        Double memUsage = parseMetric(meta, MEMORY_KEYS);

        if (cpuUsage == null && memUsage == null) {
            return currentWeight;
        }

        double maxLoad = Math.max(cpuUsage != null ? cpuUsage : 0.0, memUsage != null ? memUsage : 0.0);
        double factor = calculateLoadFactor(maxLoad, config);

        // 应用采样周期与阻尼防抖
        String instanceKey = buildInstanceKey(instance);
        long now = systemTimeProvider.getAsLong();
        long sampleIntervalMs = config.getSampleInterval() != null ? config.getSampleInterval().toMillis() : 5000L;
        double dampingRatio = config.getDampingRatio();

        evictStaleRecordsIfNecessary(now);

        CachedWeightRecord record = instanceWeightCache.compute(instanceKey, (k, oldRecord) -> {
            if (oldRecord == null) {
                return new CachedWeightRecord(now, factor);
            }
            // 若仍在采样窗口内，复用缓存中的平滑因子
            if (now - oldRecord.lastTime < sampleIntervalMs) {
                return oldRecord;
            }
            // 采样窗口已到：检查波动是否超出阻尼阈值
            double delta = Math.abs(factor - oldRecord.cachedFactor);
            if (delta < dampingRatio) {
                // 波动在容忍范围内，保持原有 factor，但刷新 lastTime 开启新的采样窗口
                return new CachedWeightRecord(now, oldRecord.cachedFactor);
            }
            // 波动超过阈值，更新阻尼状态
            return new CachedWeightRecord(now, factor);
        });

        double effectiveFactor = record != null ? record.cachedFactor : factor;
        return currentWeight * effectiveFactor;
    }

    private void evictStaleRecordsIfNecessary(long now) {
        if (instanceWeightCache.size() > MAX_CACHE_ENTRIES) {
            instanceWeightCache.entrySet().removeIf(entry -> (now - entry.getValue().lastTime) > CACHE_EXPIRE_MS);
        }
    }

    /**
     * 根据系统综合负载计算加权衰减因子 (0.10 ~ 1.0)
     */
    public double calculateLoadFactor(double load, AdaptiveWeightProperties config) {
        double mediumThreshold = config.getMediumLoadThreshold();
        double highThreshold = config.getHighLoadThreshold();
        double minFloor = config.getMinFloorWeightRatio();

        if (load <= mediumThreshold) {
            return 1.0;
        }

        if (load <= highThreshold) {
            // 中等负载阶梯：从 1.0 平滑线性衰减至 0.50
            double range = Math.max(0.001, highThreshold - mediumThreshold);
            double progress = (load - mediumThreshold) / range;
            return 1.0 - progress * (1.0 - 0.50);
        }

        // 高度过载阶梯：从 0.50 平滑线性衰减至 minFloor
        double range = Math.max(0.001, 1.0 - highThreshold);
        double progress = Math.min(1.0, (load - highThreshold) / range);
        double factor = 0.50 - progress * (0.50 - minFloor);
        return Math.max(minFloor, Math.min(1.0, factor));
    }

    /**
     * 判断某个实例是否属于严重过载状态
     */
    @Override
    public boolean isInstanceOverloaded(ServiceInstance instance) {
        if (instance == null || instance.getMetadata() == null) {
            return false;
        }
        AdaptiveWeightProperties config = getAdaptiveConfig();
        if (config == null || !config.isEnabled()) {
            return false;
        }
        Double cpuUsage = parseMetric(instance.getMetadata(), CPU_KEYS);
        Double memUsage = parseMetric(instance.getMetadata(), MEMORY_KEYS);
        if (cpuUsage == null && memUsage == null) {
            return false;
        }
        double maxLoad = Math.max(cpuUsage != null ? cpuUsage : 0.0, memUsage != null ? memUsage : 0.0);
        return maxLoad >= config.getHighLoadThreshold();
    }

    private AdaptiveWeightProperties getAdaptiveConfig() {
        if (properties != null && properties.getLoadbalancer() != null) {
            return properties.getLoadbalancer().getAdaptiveWeight();
        }
        return null;
    }

    private Double parseMetric(Map<String, String> meta, String... keys) {
        for (String key : keys) {
            String val = meta.get(key);
            if (StringUtils.hasText(val)) {
                try {
                    String trimmed = val.trim();
                    if (trimmed.endsWith("%")) {
                        trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
                        return Double.parseDouble(trimmed) / 100.0;
                    }
                    double d = Double.parseDouble(trimmed);
                    if (d > 1.0 && d <= 100.0) {
                        return d / 100.0;
                    }
                    return Math.max(0.0, Math.min(1.0, d));
                } catch (NumberFormatException e) {
                    log.debug("Failed to parse metric '{}' with value '{}': {}", key, val, e.getMessage());
                }
            }
        }
        return null;
    }

    private String buildInstanceKey(ServiceInstance inst) {
        return inst.getHost() + ":" + inst.getPort();
    }

    /**
     * 阻尼权重缓存记录
     */
    private static class CachedWeightRecord {
        private final long lastTime;
        private final double cachedFactor;

        public CachedWeightRecord(long lastTime, double cachedFactor) {
            this.lastTime = lastTime;
            this.cachedFactor = cachedFactor;
        }
    }
}
