package io.github.atengk.cloud.seata.tcc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCC Fence 分支事务日志状态枚举单测
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("TccFenceStatus 状态枚举测试")
class TccFenceStatusTest {

    @Test
    @DisplayName("测试状态码与枚举正确映射")
    void shouldMapCodeToStatusCorrectly() {
        assertThat(TccFenceStatus.fromCode(1)).isEqualTo(TccFenceStatus.TRIED);
        assertThat(TccFenceStatus.fromCode(2)).isEqualTo(TccFenceStatus.COMMITTED);
        assertThat(TccFenceStatus.fromCode(3)).isEqualTo(TccFenceStatus.ROLLBACKED);
        assertThat(TccFenceStatus.fromCode(4)).isEqualTo(TccFenceStatus.SUSPENDED);

        assertThatThrownBy(() -> TccFenceStatus.fromCode(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("测试终态判定规则")
    void shouldIdentifyFinalStatus() {
        assertThat(TccFenceStatus.TRIED.isFinal()).isFalse();
        assertThat(TccFenceStatus.COMMITTED.isFinal()).isTrue();
        assertThat(TccFenceStatus.ROLLBACKED.isFinal()).isTrue();
        assertThat(TccFenceStatus.SUSPENDED.isFinal()).isTrue();
    }

    @Test
    @DisplayName("测试合法状态转移规则")
    void shouldAllowValidStateTransitions() {
        // TRIED -> COMMITTED 合法
        assertThat(TccFenceStatus.TRIED.canTransitionTo(TccFenceStatus.COMMITTED)).isTrue();
        // TRIED -> ROLLBACKED 合法
        assertThat(TccFenceStatus.TRIED.canTransitionTo(TccFenceStatus.ROLLBACKED)).isTrue();

        // 终态不可转移至其它终态
        assertThat(TccFenceStatus.COMMITTED.canTransitionTo(TccFenceStatus.ROLLBACKED)).isFalse();
        assertThat(TccFenceStatus.ROLLBACKED.canTransitionTo(TccFenceStatus.COMMITTED)).isFalse();
        assertThat(TccFenceStatus.SUSPENDED.canTransitionTo(TccFenceStatus.COMMITTED)).isFalse();
        assertThat(TccFenceStatus.SUSPENDED.canTransitionTo(TccFenceStatus.TRIED)).isFalse();
    }
}
