package io.github.atengk.cloud.traffic.rule.hash;

/**
 * 基于 MurmurHash3 的一致性哈希粘性百分比切流分发器
 * <p>
 * 满足单用户/单实体多次请求稳定落在同一灰度泳道（会话粘性保障），杜绝状态横跳与会话震荡；
 * 同时保障放量比例扩增时的单调递增性（放量扩容不导致已有命中用户丢失灰度）。
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class StickyHashPartitioner {

    /**
     * 默认哈希种子
     */
    private static final int DEFAULT_SEED = 0;

    /**
     * 默认基准哈希桶数量 (10000 桶，对应 0.01% 粒度)
     */
    private static final int SCALE = 10000;

    private final int seed;

    public StickyHashPartitioner() {
        this(DEFAULT_SEED);
    }

    public StickyHashPartitioner(int seed) {
        this.seed = seed;
    }

    /**
     * 判定给定哈希种子键是否命中指定的整型百分比切流 (0 ~ 100)
     *
     * @param hashKey    哈希种子键 (如 userId, IP 等)
     * @param percentage 百分比 (0 ~ 100)
     * @return true 表示命中灰度放量，false 表示未命中走基线
     */
    public boolean isHit(String hashKey, int percentage) {
        return isHit(hashKey, (double) percentage);
    }

    /**
     * 判定给定哈希种子键是否命中指定的双精度百分比切流 (0.0 ~ 100.0)
     *
     * @param hashKey    哈希种子键 (如 userId, IP 等)
     * @param percentage 百分比 (0.0 ~ 100.0)
     * @return true 表示命中灰度放量，false 表示未命中走基线
     */
    public boolean isHit(String hashKey, double percentage) {
        if (percentage <= 0.0) {
            return false;
        }
        if (percentage >= 100.0) {
            return true;
        }
        if (hashKey == null || hashKey.isBlank()) {
            return false;
        }

        // 计算 0 ~ 9999 的均匀离散桶号
        int bucket = calculateBucket(hashKey, SCALE);
        long threshold = Math.round(percentage * (SCALE / 100.0));
        return bucket < threshold;
    }

    /**
     * 计算给定哈希键在指定桶总数中的绝对桶号 (范围: 0 ~ totalBuckets - 1)
     *
     * @param hashKey      哈希种子键
     * @param totalBuckets 桶总数 (必须 > 0)
     * @return 落在的桶号
     */
    public int calculateBucket(String hashKey, int totalBuckets) {
        if (totalBuckets <= 0) {
            throw new IllegalArgumentException("totalBuckets must be greater than 0");
        }
        if (hashKey == null) {
            return 0;
        }
        int hash = MurmurHash3.hash32(hashKey, this.seed);
        long unsignedHash = Integer.toUnsignedLong(hash);
        return (int) (unsignedHash % totalBuckets);
    }

    public int getSeed() {
        return seed;
    }
}
