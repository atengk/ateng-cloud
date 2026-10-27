package io.github.atengk.cloud.nacos.loadbalancer;

import io.github.atengk.cloud.nacos.metadata.DefaultNacosMetadataProvider;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Nacos 服务实例同机房可用区 (Zone Affinity) 就近亲和提供者
 *
 * <p>核心治理语义：
 * 1. 优先筛选与当前调用端处于相同机房区域（Zone）的健康可用实例；
 * 2. 严格过滤 Nacos 动态权重 <= 0.0 或禁用的下线实例；
 * 3. 当本机房无任何可用健康实例时，平滑降级（Cross-Zone Fallback）至全集群健康实例，防御机房级雪崩。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class ZonePreferenceServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    private static final Logger log = LoggerFactory.getLogger(ZonePreferenceServiceInstanceListSupplier.class);

    private final AtengNacosProperties properties;

    public ZonePreferenceServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
                                                     AtengNacosProperties properties) {
        super(delegate);
        this.properties = properties;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return getDelegate().get().map(this::filterByZone);
    }

    @Override
    public Flux<List<ServiceInstance>> get(Request request) {
        return getDelegate().get(request).map(this::filterByZone);
    }

    /**
     * 执行机房就近亲和与有效性过滤
     *
     * @param instances 原始实例集合
     * @return 过滤或降级后的实例列表（保证非 null）
     */
    public List<ServiceInstance> filterByZone(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 若配置关闭了同机房亲和，直接返回可路由实例
        if (properties == null || properties.getLoadbalancer() == null
                || !properties.getLoadbalancer().isZoneAffinity()) {
            return filterRoutableInstances(instances);
        }

        // 2. 解析当前节点归属的机房标识
        String targetZone = resolveTargetZone();
        if (!StringUtils.hasText(targetZone)) {
            return filterRoutableInstances(instances);
        }

        // 3. 过滤可路由节点（剔除 weight <= 0.0 或 disabled 实例）
        List<ServiceInstance> routableInstances = filterRoutableInstances(instances);
        if (routableInstances.isEmpty()) {
            log.warn("[ateng-cloud-nacos] All candidate service instances are disabled or have zero weight.");
            return Collections.emptyList();
        }

        // 4. 筛选同机房健康实例
        List<ServiceInstance> sameZoneInstances = routableInstances.stream()
                .filter(inst -> {
                    Map<String, String> meta = inst.getMetadata();
                    if (meta == null) {
                        return false;
                    }
                    String zone = meta.get(DefaultNacosMetadataProvider.KEY_ZONE);
                    return targetZone.equalsIgnoreCase(zone);
                })
                .toList();

        // 5. 若本机房存在可用健康实例，同机房就近优先路由
        if (!sameZoneInstances.isEmpty()) {
            log.debug("[ateng-cloud-nacos] Zone affinity hit: selected {} instances in target zone '{}'",
                    sameZoneInstances.size(), targetZone);
            return Collections.unmodifiableList(sameZoneInstances);
        }

        // 6. 本机房全宕或无实例，平滑跨区容灾降级使用全集群健康实例
        log.warn("[ateng-cloud-nacos] No healthy instances found in target zone '{}', falling back to cross-zone instances (total: {})",
                targetZone, routableInstances.size());
        return Collections.unmodifiableList(routableInstances);
    }

    /**
     * 过滤权重有效（weight > 0.0）且健康启用的可路由实例
     */
    private List<ServiceInstance> filterRoutableInstances(List<ServiceInstance> instances) {
        return instances.stream()
                .filter(inst -> {
                    if (inst == null) {
                        return false;
                    }
                    Map<String, String> meta = inst.getMetadata();
                    if (meta != null) {
                        // 检查 Nacos 动态权重（0.0 视为流量隔离下线）
                        String weightStr = meta.get("nacos.weight");
                        if (StringUtils.hasText(weightStr)) {
                            try {
                                if (Double.parseDouble(weightStr) <= 0.0) {
                                    return false;
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        // 检查健康状态标记
                        String healthyStr = meta.get("nacos.healthy");
                        if (StringUtils.hasText(healthyStr) && "false".equalsIgnoreCase(healthyStr)) {
                            return false;
                        }
                    }
                    return true;
                })
                .toList();
    }

    /**
     * 解析当前节点的目标机房区域标识
     */
    public String resolveTargetZone() {
        if (properties != null) {
            if (StringUtils.hasText(properties.getZone())) {
                return properties.getZone();
            }
            if (properties.getMetadata() != null && StringUtils.hasText(properties.getMetadata().getZone())) {
                return properties.getMetadata().getZone();
            }
        }
        String envZone = System.getProperty("ZONE", System.getenv("ZONE"));
        if (StringUtils.hasText(envZone)) {
            return envZone;
        }
        return System.getProperty("REGION", System.getenv("REGION"));
    }
}
