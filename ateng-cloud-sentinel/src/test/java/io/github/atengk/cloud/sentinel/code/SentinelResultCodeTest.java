package io.github.atengk.cloud.sentinel.code;

import io.github.atengk.boot.core.code.ResultCodeSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SentinelResultCode} 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class SentinelResultCodeTest {

    @Test
    @DisplayName("错误码规范性：所有错误码必须属于 NETWORK_RPC (4000-4999) 码段且编码在 4200~4299 子区间")
    void testResultCodeSegmentCompliance() {
        for (SentinelResultCode code : SentinelResultCode.values()) {
            assertThat(code.getCode()).isNotNull();
            assertThat(code.getMsg()).isNotBlank();
            assertThat(ResultCodeSegment.NETWORK_RPC.contains(code.getCode()))
                    .as("Error code %s (%d) should be within NETWORK_RPC segment", code.name(), code.getCode())
                    .isTrue();
            assertThat(code.getCode())
                    .as("Error code %s (%d) should be in Sentinel range [4200, 4299]", code.name(), code.getCode())
                    .isBetween(4200, 4299);
        }
    }
}
