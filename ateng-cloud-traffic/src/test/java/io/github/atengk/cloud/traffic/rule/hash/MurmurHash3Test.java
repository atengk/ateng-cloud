package io.github.atengk.cloud.traffic.rule.hash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MurmurHash3 算法单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class MurmurHash3Test {

    @Test
    @DisplayName("空字符串与 null 边界测试")
    void testEmptyAndNull() {
        int hashNull = MurmurHash3.hash32(null);
        int hashEmpty = MurmurHash3.hash32("");
        assertThat(hashNull).isEqualTo(0);
        assertThat(hashEmpty).isEqualTo(0);

        int hashNullSeed = MurmurHash3.hash32(null, 42);
        int hashEmptySeed = MurmurHash3.hash32("", 42);
        assertThat(hashNullSeed).isEqualTo(hashEmptySeed);
    }

    @Test
    @DisplayName("确定性哈希与不同长度字节对齐测试")
    void testDeterminismAndLengths() {
        // 覆盖 1字节、2字节、3字节、4字节（整块）、5字节（尾部1字节）、7字节（尾部3字节）等不同对齐
        String[] samples = {
                "a",
                "ab",
                "abc",
                "abcd",
                "abcde",
                "abcdef",
                "abcdefg",
                "abcdefgh",
                "order_service_canary_v2",
                "192.168.1.100"
        };

        for (String sample : samples) {
            int hash1 = MurmurHash3.hash32(sample);
            int hash2 = MurmurHash3.hash32(sample);
            assertThat(hash1).isEqualTo(hash2);

            byte[] bytes = sample.getBytes(StandardCharsets.UTF_8);
            int byteHash = MurmurHash3.hash32(bytes, 0, bytes.length, 0);
            assertThat(hash1).isEqualTo(byteHash);
        }
    }

    @Test
    @DisplayName("不同种子产生不同哈希值测试")
    void testDifferentSeeds() {
        String input = "user_10086";
        int hash1 = MurmurHash3.hash32(input, 0);
        int hash2 = MurmurHash3.hash32(input, 12345);
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("大样本离散碰撞率与分布测试")
    void testAvalancheAndCollision() {
        int count = 10000;
        Set<Integer> hashes = new HashSet<>(count);
        for (int i = 0; i < count; i++) {
            String key = "test_user_key_" + UUID.randomUUID();
            hashes.add(MurmurHash3.hash32(key));
        }
        // 10000 个随机 UUID 的 32 位 MurmurHash3 碰撞数应极低 (< 5)
        assertThat(hashes.size()).isGreaterThanOrEqualTo(count - 5);
    }
}
