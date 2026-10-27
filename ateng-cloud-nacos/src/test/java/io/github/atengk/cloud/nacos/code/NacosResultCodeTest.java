package io.github.atengk.cloud.nacos.code;

import io.github.atengk.boot.core.code.ResultCodeSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NacosResultCode} 单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class NacosResultCodeTest {

    @Test
    @DisplayName("错误码规范性：所有错误码必须属于 NETWORK_RPC (4000-4999) 码段")
    void testResultCodeSegmentCompliance() {
        for (NacosResultCode code : NacosResultCode.values()) {
            assertThat(code.getCode()).isNotNull();
            assertThat(code.getMsg()).isNotBlank();
            assertThat(ResultCodeSegment.NETWORK_RPC.contains(code.getCode()))
                    .as("Error code %s (%d) should be within NETWORK_RPC segment", code.name(), code.getCode())
                    .isTrue();
        }
    }
}
