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

    @Test
    @DisplayName("isHitRange 正常区间分桶与完全覆盖互斥测试")
    void testIsHitRangeNormalAndExclusion() {
        // [0, 100) 必须 100% 命中所有非空用户
        assertThat(partitioner.isHitRange("user_abc", 0, 100)).isTrue();
        assertThat(partitioner.isHitRange("user_xyz", 0, 100)).isTrue();

        // 验证三段区间 [0, 20), [20, 50), [50, 100) 互斥且完备
        int sampleCount = 5000;
        int hitRange1 = 0;
        int hitRange2 = 0;
        int hitRange3 = 0;

        for (int i = 0; i < sampleCount; i++) {
            String userId = "range_user_" + i;
            boolean in1 = partitioner.isHitRange(userId, 0, 20);
            boolean in2 = partitioner.isHitRange(userId, 20, 50);
            boolean in3 = partitioner.isHitRange(userId, 50, 100);

            // 严格互斥：任何用户必须且只能命中其中一段
            int hitCount = (in1 ? 1 : 0) + (in2 ? 1 : 0) + (in3 ? 1 : 0);
            assertThat(hitCount).as("用户 %s 必须且只能命中一个分桶区间", userId).isEqualTo(1);

            if (in1) hitRange1++;
            if (in2) hitRange2++;
            if (in3) hitRange3++;
        }

        // 验证各区间占比接近理论值 (20%, 30%, 50%)
        assertThat((double) hitRange1 / sampleCount).isBetween(0.18, 0.22);
        assertThat((double) hitRange2 / sampleCount).isBetween(0.28, 0.32);
        assertThat((double) hitRange3 / sampleCount).isBetween(0.48, 0.52);
    }

    @Test
    @DisplayName("isHitRange 非法区间与边界越界防御测试 (绝不抛出未受管异常)")
    void testIsHitRangeInvalidRangesDefenses() {
        // min 负数
        assertThat(partitioner.isHitRange("user_1", -1, 50)).isFalse();
        // max 超过 100
        assertThat(partitioner.isHitRange("user_1", 10, 101)).isFalse();
        // min >= max (颠倒或相等)
        assertThat(partitioner.isHitRange("user_1", 50, 50)).isFalse();
        assertThat(partitioner.isHitRange("user_1", 60, 40)).isFalse();
        // 空或空白 hashKey
        assertThat(partitioner.isHitRange(null, 0, 50)).isFalse();
        assertThat(partitioner.isHitRange("", 0, 50)).isFalse();
        assertThat(partitioner.isHitRange("   ", 0, 50)).isFalse();
    }

    @Test
    @DisplayName("isHitRange 临界边界测试 (测试 0, 99, 100 临界分桶)")
    void testIsHitRangeCriticalBoundaries() {
        // [0, 1) 仅当分桶号正好为 0 时命中
        // [99, 100) 仅当分桶号正好为 99 时命中
        boolean found0 = false;
        boolean found99 = false;
        for (int i = 0; i < 2000; i++) {
            String key = "test_boundary_key_" + i;
            int bucket = partitioner.partition(key);
            if (bucket == 0) {
                assertThat(partitioner.isHitRange(key, 0, 1)).isTrue();
                assertThat(partitioner.isHitRange(key, 1, 100)).isFalse();
                found0 = true;
            }
            if (bucket == 99) {
                assertThat(partitioner.isHitRange(key, 99, 100)).isTrue();
                assertThat(partitioner.isHitRange(key, 0, 99)).isFalse();
                found99 = true;
            }
            if (found0 && found99) {
                break;
            }
        }
        assertThat(found0).as("应当能够采样到分桶为 0 的样本").isTrue();
        assertThat(found99).as("应当能够采样到分桶为 99 的边界样本").isTrue();

        // 静态方法 isValidRange 校验
        assertThat(StickyHashPartitioner.isValidRange(0, 1)).isTrue();
        assertThat(StickyHashPartitioner.isValidRange(99, 100)).isTrue();
        assertThat(StickyHashPartitioner.isValidRange(0, 100)).isTrue();
        assertThat(StickyHashPartitioner.isValidRange(0, 0)).isFalse();
        assertThat(StickyHashPartitioner.isValidRange(100, 100)).isFalse();
        assertThat(StickyHashPartitioner.isValidRange(99, 99)).isFalse();
        assertThat(StickyHashPartitioner.isValidRange(100, 99)).isFalse();
        assertThat(StickyHashPartitioner.isValidRange(-1, 99)).isFalse();
        assertThat(StickyHashPartitioner.isValidRange(0, 101)).isFalse();
    }

    @Test
    @DisplayName("isHitRange 会话粘性保障测试")
    void testIsHitRangeSessionStickiness() {
        String userId = "sticky_range_user_999";
        boolean firstResult = partitioner.isHitRange(userId, 15, 35);
        for (int i = 0; i < 50; i++) {
            assertThat(partitioner.isHitRange(userId, 15, 35)).isEqualTo(firstResult);
        }
    }
}
