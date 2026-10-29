package io.github.atengk.cloud.traffic.rule.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流量规则多源合并策略枚举单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class TrafficRuleMergeStrategyTest {

    @Test
    @DisplayName("fromString 转换与安全回退测试")
    void testFromString() {
        assertThat(TrafficRuleMergeStrategy.fromString("OVERWRITE")).isEqualTo(TrafficRuleMergeStrategy.OVERWRITE);
        assertThat(TrafficRuleMergeStrategy.fromString("overwrite")).isEqualTo(TrafficRuleMergeStrategy.OVERWRITE);
        assertThat(TrafficRuleMergeStrategy.fromString("MERGE")).isEqualTo(TrafficRuleMergeStrategy.MERGE);
        assertThat(TrafficRuleMergeStrategy.fromString("merge")).isEqualTo(TrafficRuleMergeStrategy.MERGE);
        assertThat(TrafficRuleMergeStrategy.fromString(null)).isEqualTo(TrafficRuleMergeStrategy.OVERWRITE);
        assertThat(TrafficRuleMergeStrategy.fromString("")).isEqualTo(TrafficRuleMergeStrategy.OVERWRITE);
        assertThat(TrafficRuleMergeStrategy.fromString("unknown")).isEqualTo(TrafficRuleMergeStrategy.OVERWRITE);
    }
}
