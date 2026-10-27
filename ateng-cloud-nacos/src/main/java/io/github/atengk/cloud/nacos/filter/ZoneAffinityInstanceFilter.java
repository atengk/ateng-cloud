package io.github.atengk.cloud.nacos.filter;

import com.alibaba.nacos.api.naming.pojo.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Nacos 服务实例同机房 (Zone) 就近亲和过滤器
 *
 * <p>核心治理语义：
 * 1. 优先筛选与当前调用端处于相同机房区域 (zone) 的健康可用实例；
 * 2. 严格响应 Nacos 动态权重治理，权重 <= 0.0 或已禁用的节点自动过滤；
 * 3. 当本机房无任何可用健康实例时，平滑降级 (Cross-Zone Fallback) 至全集群健康实例，防御机房级雪崩。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class ZoneAffinityInstanceFilter {

    private static final Logger log = LoggerFactory.getLogger(ZoneAffinityInstanceFilter.class);

    /**
     * 机房区域元数据键名
     */
    public static final String METADATA_KEY_ZONE = "zone";

    /**
     * 执行机房就近亲和过滤
     *
     * @param instances  原始候选实例列表
     * @param targetZone 目标机房区域标识 (如 beijing-az1)
     * @return 过滤或降级后的实例列表 (空安全保证，绝不返回 null)
     */
    public List<Instance> filter(List<Instance> instances, String targetZone) {
        if (instances == null || instances.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 严格过滤权重 <= 0.0、禁用或不健康的无效实例
        List<Instance> routableInstances = instances.stream()
                .filter(inst -> inst != null && inst.isEnabled() && inst.isHealthy() && inst.getWeight() > 0.0)
                .collect(Collectors.toList());

        if (routableInstances.isEmpty()) {
            log.warn("All candidate instances are disabled, unhealthy, or have zero weight (weight <= 0.0).");
            return Collections.emptyList();
        }

        // 2. 若未配置目标机房标识，直接返回全量有效实例
        if (!StringUtils.hasText(targetZone)) {
            return Collections.unmodifiableList(routableInstances);
        }

        // 3. 筛选本机房实例
        List<Instance> sameZoneInstances = routableInstances.stream()
                .filter(inst -> {
                    if (inst.getMetadata() == null) {
                        return false;
                    }
                    String zone = inst.getMetadata().get(METADATA_KEY_ZONE);
                    return targetZone.equalsIgnoreCase(zone);
                })
                .collect(Collectors.toList());

        // 4. 若本机房存在可用实例，同机房优先路由
        if (!sameZoneInstances.isEmpty()) {
            log.debug("Zone affinity hit: found {} instances in target zone '{}'", sameZoneInstances.size(), targetZone);
            return Collections.unmodifiableList(sameZoneInstances);
        }

        // 5. 本机房无实例，平滑降级使用全集群健康实例 (Cross-Zone Fallback)
        log.warn("No healthy instances found in target zone '{}', falling back to cross-zone instances (total: {})",
                targetZone, routableInstances.size());
        return Collections.unmodifiableList(routableInstances);
    }
}
