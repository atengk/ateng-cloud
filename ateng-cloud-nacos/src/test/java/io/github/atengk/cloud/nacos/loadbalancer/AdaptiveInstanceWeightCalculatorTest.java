package io.github.atengk.cloud.nacos.loadbalancer;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link AdaptiveInstanceWeightCalculator} 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AdaptiveInstanceWeightCalculatorTest {

    private ServiceInstance createInstance(String id, String host, int port, String cpuUsage, String memUsage) {
        Map<String, String> metadata = new HashMap<>();
        if (cpuUsage != null) {
            metadata.put("cpu-usage", cpuUsage);
        }
        if (memUsage != null) {
            metadata.put("memory-usage", memUsage);
        }
        return new DefaultServiceInstance(id, "order-service", host, port, false, metadata);
    }

    @Test
    @DisplayName("测试无资源指标元数据时保持原始权重")
    void testNoResourceMetadataReturnsOriginalWeight() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getLoadbalancer().getAdaptiveWeight().setEnabled(true);
        AdaptiveInstanceWeightCalculator calculator = new AdaptiveInstanceWeightCalculator(properties);

        ServiceInstance instance = createInstance("inst-1", "10.0.0.1", 8080, null, null);
        double weight = calculator.calculateWeight(instance, 1.0);
        assertThat(weight).isEqualTo(1.0);
    }

    @Test
    @DisplayName("测试未开启自适应加权时直接放行原权重")
    void testDisabledReturnsOriginalWeight() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getLoadbalancer().getAdaptiveWeight().setEnabled(false);
        AdaptiveInstanceWeightCalculator calculator = new AdaptiveInstanceWeightCalculator(properties);

        ServiceInstance instance = createInstance("inst-1", "10.0.0.1", 8080, "0.95", null);
        double weight = calculator.calculateWeight(instance, 1.0);
        assertThat(weight).isEqualTo(1.0);
    }

    @Test
    @DisplayName("测试健康负载 (<= 70%) 时保持 100% 原始权重")
    void testHealthyLoadReturnsOriginalWeight() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getLoadbalancer().getAdaptiveWeight().setEnabled(true);
        AdaptiveInstanceWeightCalculator calculator = new AdaptiveInstanceWeightCalculator(properties);

        ServiceInstance instance = createInstance("inst-1", "10.0.0.1", 8080, "0.50", "45%");
        double weight = calculator.calculateWeight(instance, 1.0);
        assertThat(weight).isEqualTo(1.0);
    }

    @Test
    @DisplayName("测试中度负载 (70% ~ 85%) 时平滑线性降权")
    void testMediumLoadModeratePenalty() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getLoadbalancer().getAdaptiveWeight().setEnabled(true);
        AdaptiveInstanceWeightCalculator calculator = new AdaptiveInstanceWeightCalculator(properties);

        // 77.5% 负载，处于 70% 与 85% 正中，权重应折损至约 0.75
        ServiceInstance instance = createInstance("inst-1", "10.0.0.1", 8080, "0.775", null);
        double weight = calculator.calculateWeight(instance, 1.0);
        assertThat(weight).isCloseTo(0.75, within(0.01));
    }

    @Test
    @DisplayName("测试重度负载 (> 85%) 时快速降权至接近底噪")
    void testHighLoadSeverePenalty() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getLoadbalancer().getAdaptiveWeight().setEnabled(true);
        AdaptiveInstanceWeightCalculator calculator = new AdaptiveInstanceWeightCalculator(properties);

        // 95% 负载，处于 85% 与 100% 之间 (进度 2/3，权重从 0.50 线性衰减至 0.233)
        ServiceInstance instance = createInstance("inst-1", "10.0.0.1", 8080, "95%", null);
        double weight = calculator.calculateWeight(instance, 1.0);
        assertThat(weight).isCloseTo(0.233, within(0.01));
    }

    @Test
    @DisplayName("测试保底底噪防线：即使 100% 极限过载也不低于 minFloorWeightRatio (10%)")
    void testMinFloorWeightRatioEnforced() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getLoadbalancer().getAdaptiveWeight().setEnabled(true);
        properties.getLoadbalancer().getAdaptiveWeight().setMinFloorWeightRatio(0.10);
        AdaptiveInstanceWeightCalculator calculator = new AdaptiveInstanceWeightCalculator(properties);

        ServiceInstance instance = createInstance("inst-1", "10.0.0.1", 8080, "1.00", "99%");
        double weight = calculator.calculateWeight(instance, 1.0);
        assertThat(weight).isCloseTo(0.10, within(0.001));
    }

    @Test
    @DisplayName("测试采样周期与阻尼防抖：小幅波动 (< 15%) 维持缓存权重")
    void testDampingPreventsFlapping() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getLoadbalancer().getAdaptiveWeight().setEnabled(true);
        properties.getLoadbalancer().getAdaptiveWeight().setSampleInterval(Duration.ofSeconds(10));
        properties.getLoadbalancer().getAdaptiveWeight().setDampingRatio(0.15);

        java.util.concurrent.atomic.AtomicLong currentTime = new java.util.concurrent.atomic.AtomicLong(1000000L);
        AdaptiveInstanceWeightCalculator calculator = new AdaptiveInstanceWeightCalculator(properties, currentTime::get);

        // 初始计算：77.5% 负载，权重为 0.75
        ServiceInstance instance = createInstance("inst-1", "10.0.0.2", 8080, "0.775", null);
        double initialWeight = calculator.calculateWeight(instance, 1.0);
        assertThat(initialWeight).isCloseTo(0.75, within(0.01));

        // 模拟 1 秒后负载微降至 75% (波动很小，未过采样周期且未超 15% 阻尼)，应复用缓存权重
        currentTime.addAndGet(1000L);
        ServiceInstance instanceSmallFlap = createInstance("inst-1", "10.0.0.2", 8080, "0.750", null);
        double cachedWeight = calculator.calculateWeight(instanceSmallFlap, 1.0);
        assertThat(cachedWeight).isEqualTo(initialWeight);

        // 模拟 11 秒后（超过 10 秒采样周期），但波动依然微小 (< 15%)，保持缓存值并刷新采样窗口
        currentTime.addAndGet(10000L);
        double dampedWeightAfterInterval = calculator.calculateWeight(instanceSmallFlap, 1.0);
        assertThat(dampedWeightAfterInterval).isEqualTo(initialWeight);

        // 模拟再过 1 秒负载剧烈降至 40% (delta 0.35 > 0.15)，但仍在新的 10s 采样窗口内，保持平稳防抖
        currentTime.addAndGet(1000L);
        ServiceInstance instanceBigFlapInWindow = createInstance("inst-1", "10.0.0.2", 8080, "0.400", null);
        assertThat(calculator.calculateWeight(instanceBigFlapInWindow, 1.0)).isEqualTo(initialWeight);

        // 模拟窗口结束后 (再过 10 秒)，剧烈降至 40% 被正式采纳，权重恢复为 1.0
        currentTime.addAndGet(10000L);
        assertThat(calculator.calculateWeight(instanceBigFlapInWindow, 1.0)).isEqualTo(1.0);
    }
}
