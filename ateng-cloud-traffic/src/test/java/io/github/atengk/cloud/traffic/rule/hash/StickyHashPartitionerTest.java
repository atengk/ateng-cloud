package io.github.atengk.cloud.traffic.rule.hash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一致性哈希粘性百分比切流分发器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class StickyHashPartitionerTest {

    private final StickyHashPartitioner partitioner = new StickyHashPartitioner();

    @Test
    @DisplayName("百分比极值边界测试 (0% 与 100%)")
    void testBoundaryPercentages() {
        assertThat(partitioner.isHit("user_123", 0)).isFalse();
        assertThat(partitioner.isHit("user_123", -10)).isFalse();
        assertThat(partitioner.isHit("user_123", 0.0)).isFalse();

        assertThat(partitioner.isHit("user_123", 100)).isTrue();
        assertThat(partitioner.isHit("user_123", 101)).isTrue();
        assertThat(partitioner.isHit("user_123", 100.0)).isTrue();
    }

    @Test
    @DisplayName("非法与空哈希键防御测试")
    void testBlankAndNullHashKey() {
        assertThat(partitioner.isHit(null, 50)).isFalse();
        assertThat(partitioner.isHit("", 50)).isFalse();
        assertThat(partitioner.isHit("   ", 50)).isFalse();
    }

    @Test
    @DisplayName("会话粘性保障测试 (同一实体多次请求结果必须 100% 绝对一致)")
    void testSessionStickiness() {
        String userId = "user_88888";
        boolean firstResult = partitioner.isHit(userId, 30);
        for (int i = 0; i < 100; i++) {
            assertThat(partitioner.isHit(userId, 30)).isEqualTo(firstResult);
        }
    }

    @Test
    @DisplayName("单调递增性测试 (放量比例增加时，原本命中的用户不会跌出灰度)")
    void testMonotonicity() {
        int sampleCount = 1000;
        for (int i = 0; i < sampleCount; i++) {
            String userId = "user_mono_" + i;
            if (partitioner.isHit(userId, 20)) {
                // 如果在 20% 时命中，那么在 30%、50%、80% 时必须持续命中
                assertThat(partitioner.isHit(userId, 30)).isTrue();
                assertThat(partitioner.isHit(userId, 50)).isTrue();
                assertThat(partitioner.isHit(userId, 80)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("大样本均匀离散度测试 (20% 与 50% 放量误差控制在合理区间)")
    void testUniformDistribution() {
        int sampleCount = 20000;
        int hitCount20 = 0;
        int hitCount50 = 0;

        for (int i = 0; i < sampleCount; i++) {
            String userId = "uid_" + UUID.randomUUID();
            if (partitioner.isHit(userId, 20)) {
                hitCount20++;
            }
            if (partitioner.isHit(userId, 50)) {
                hitCount50++;
            }
        }

        double ratio20 = (double) hitCount20 / sampleCount;
        double ratio50 = (double) hitCount50 / sampleCount;

        // 20% 切流实际比例应在 18.5% ~ 21.5% 之间
        assertThat(ratio20).isBetween(0.185, 0.215);
        // 50% 切流实际比例应在 48.0% ~ 52.0% 之间
        assertThat(ratio50).isBetween(0.480, 0.520);
    }

    @Test
    @DisplayName("双精度百分比切流测试 (例如 12.5%)")
    void testDoublePercentage() {
        int sampleCount = 10000;
        int hitCount = 0;
        for (int i = 0; i < sampleCount; i++) {
            String userId = "uid_double_" + i;
            if (partitioner.isHit(userId, 12.5)) {
                hitCount++;
            }
        }
        double ratio = (double) hitCount / sampleCount;
        assertThat(ratio).isBetween(0.11, 0.14);
    }

    @Test
    @DisplayName("calculateBucket 异常入参防御测试")
    void testCalculateBucketInvalidArgs() {
        assertThatThrownBy(() -> partitioner.calculateBucket("test", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> partitioner.calculateBucket("test", -1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(partitioner.calculateBucket(null, 10)).isEqualTo(0);
        assertThat(partitioner.getSeed()).isEqualTo(0);
    }
}
