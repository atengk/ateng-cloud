package io.github.atengk.cloud.traffic.rule.hash;

import java.nio.charset.StandardCharsets;

/**
 * 纳秒级高性能非加密 MurmurHash3 32-bit (x86) 哈希算法实现
 * <p>
 * 提供优异的雪崩效应（Avalanche Effect）与离散均匀分布，专用于会话粘性哈希切流与抽样计算。
 *
 * @author Ateng
 * @since 2026-10-29
 */
public final class MurmurHash3 {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;
    private static final int DEFAULT_SEED = 0;

    private MurmurHash3() {
    }

    /**
     * 计算字符串的 32 位 MurmurHash3 哈希值（默认 Seed 为 0）
     *
     * @param text 输入字符串
     * @return 32 位哈希值
     */
    public static int hash32(String text) {
        return hash32(text, DEFAULT_SEED);
    }

    /**
     * 计算字符串的 32 位 MurmurHash3 哈希值
     *
     * @param text 输入字符串
     * @param seed 初始种子
     * @return 32 位哈希值
     */
    public static int hash32(String text, int seed) {
        if (text == null || text.isEmpty()) {
            return hash32(new byte[0], 0, 0, seed);
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return hash32(bytes, 0, bytes.length, seed);
    }

    /**
     * 计算指定字节数组切片的 32 位 MurmurHash3 哈希值
     *
     * @param data   字节数组
     * @param offset 起始偏移量
     * @param length 字节长度
     * @param seed   哈希种子
     * @return 32 位哈希值
     */
    public static int hash32(byte[] data, int offset, int length, int seed) {
        if (data == null || length <= 0) {
            return fmix32(seed);
        }

        int h1 = seed;
        int nblocks = length >> 2;

        // body
        for (int i = 0; i < nblocks; i++) {
            int i4 = offset + (i << 2);
            int k1 = (data[i4] & 0xff)
                    | ((data[i4 + 1] & 0xff) << 8)
                    | ((data[i4 + 2] & 0xff) << 16)
                    | ((data[i4 + 3] & 0xff) << 24);

            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        // tail
        int tailOffset = offset + (nblocks << 2);
        int k1 = 0;
        switch (length & 3) {
            case 3:
                k1 ^= (data[tailOffset + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 ^= (data[tailOffset + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 ^= (data[tailOffset] & 0xff);
                k1 *= C1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= C2;
                h1 ^= k1;
                break;
            default:
                break;
        }

        // finalization
        h1 ^= length;
        return fmix32(h1);
    }

    /**
     * 32 位雪崩混合函数 (fmix32)
     *
     * @param h 输入哈希
     * @return 混合后的哈希
     */
    private static int fmix32(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
