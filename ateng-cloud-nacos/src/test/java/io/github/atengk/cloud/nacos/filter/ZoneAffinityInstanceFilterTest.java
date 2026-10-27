package io.github.atengk.cloud.nacos.filter;

import com.alibaba.nacos.api.naming.pojo.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ZoneAffinityInstanceFilter} 单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class ZoneAffinityInstanceFilterTest {

    private final ZoneAffinityInstanceFilter filter = new ZoneAffinityInstanceFilter();

    @Test
    @DisplayName("空安全：传入空集合或 null 保证返回空集合")
    void testEmptySafety() {
        assertThat(filter.filter(null, "zone-a")).isEmpty();
        assertThat(filter.filter(Collections.emptyList(), "zone-a")).isEmpty();
    }

    @Test
    @DisplayName("动态权重与健康过滤：排除 weight <= 0.0、禁用或不健康节点")
    void testZeroWeightAndDisabledFiltering() {
        Instance disabledInst = createInstance("192.168.1.1", 8080, "zone-a", 1.0, true, false);
        Instance zeroWeightInst = createInstance("192.168.1.2", 8080, "zone-a", 0.0, true, true);
        Instance unhealthyInst = createInstance("192.168.1.3", 8080, "zone-a", 1.0, false, true);

        List<Instance> result = filter.filter(List.of(disabledInst, zeroWeightInst, unhealthyInst), "zone-a");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("机房亲和优先：当本机房存在健康可用实例时优先返回")
    void testZoneAffinityHit() {
        Instance instZoneA1 = createInstance("192.168.1.10", 8080, "zone-a", 1.0, true, true);
        Instance instZoneA2 = createInstance("192.168.1.11", 8080, "zone-a", 1.0, true, true);
        Instance instZoneB = createInstance("192.168.2.10", 8080, "zone-b", 1.0, true, true);

        List<Instance> result = filter.filter(List.of(instZoneA1, instZoneA2, instZoneB), "zone-a");
        assertThat(result).hasSize(2)
                .extracting(Instance::getIp)
                .containsExactlyInAnyOrder("192.168.1.10", "192.168.1.11");
    }

    @Test
    @DisplayName("跨区平滑降级：本机房无可用实例时自动降级返回全集群健康实例")
    void testCrossZoneFallback() {
        Instance instZoneB = createInstance("192.168.2.10", 8080, "zone-b", 1.0, true, true);
        Instance instZoneC = createInstance("192.168.3.10", 8080, "zone-c", 1.0, true, true);

        // 请求 zone-a，但列表中只有 zone-b 和 zone-c
        List<Instance> result = filter.filter(List.of(instZoneB, instZoneC), "zone-a");
        assertThat(result).hasSize(2)
                .extracting(Instance::getIp)
                .containsExactlyInAnyOrder("192.168.2.10", "192.168.3.10");
    }

    @Test
    @DisplayName("无机房指定：targetZone 为空时直接返回全量有效健康实例")
    void testNoZoneSpecified() {
        Instance inst1 = createInstance("192.168.1.10", 8080, "zone-a", 1.0, true, true);
        Instance inst2 = createInstance("192.168.2.10", 8080, "zone-b", 1.0, true, true);

        List<Instance> result = filter.filter(List.of(inst1, inst2), null);
        assertThat(result).hasSize(2);

        List<Instance> resultBlank = filter.filter(List.of(inst1, inst2), "   ");
        assertThat(resultBlank).hasSize(2);
    }

    private Instance createInstance(String ip, int port, String zone, double weight, boolean healthy, boolean enabled) {
        Instance inst = new Instance();
        inst.setIp(ip);
        inst.setPort(port);
        inst.setWeight(weight);
        inst.setHealthy(healthy);
        inst.setEnabled(enabled);
        Map<String, String> meta = new HashMap<>();
        if (zone != null) {
            meta.put("zone", zone);
        }
        inst.setMetadata(meta);
        return inst;
    }
}
