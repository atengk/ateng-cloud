package io.github.atengk.cloud.nacos.metadata;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NacosWarmupHelper} 单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
class NacosWarmupHelperTest {

    @Test
    @DisplayName("预热时长解析：正确处理秒数后缀与保底缺省值")
    void testParseDurationToSeconds() {
        assertThat(NacosWarmupHelper.parseDurationToSeconds("60s", 30L)).isEqualTo(60L);
        assertThat(NacosWarmupHelper.parseDurationToSeconds("120S", 30L)).isEqualTo(120L);
        assertThat(NacosWarmupHelper.parseDurationToSeconds("45", 30L)).isEqualTo(45L);
        assertThat(NacosWarmupHelper.parseDurationToSeconds("", 30L)).isEqualTo(30L);
        assertThat(NacosWarmupHelper.parseDurationToSeconds(null, 30L)).isEqualTo(30L);
        assertThat(NacosWarmupHelper.parseDurationToSeconds("invalid", 30L)).isEqualTo(30L);
        assertThat(NacosWarmupHelper.parseDurationToSeconds("-10s", 30L)).isEqualTo(30L);
    }

    @Test
    @DisplayName("预热状态计算：处于预热期内时，计算剩余倒计时且完成态为 false")
    void testCalculateWarmupStatusDuringWarmup() {
        long now = System.currentTimeMillis();
        long startupTime = now - 20_000L; // 启动了 20 秒

        NacosMetadataProvider metadataProvider = mock(NacosMetadataProvider.class);
        when(metadataProvider.getMetadata()).thenReturn(Map.of(
                DefaultNacosMetadataProvider.KEY_STARTUP_TIME, String.valueOf(startupTime),
                DefaultNacosMetadataProvider.KEY_WARMUP_DURATION, "60s"
        ));

        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getMetadata().setWarmupDuration(Duration.ofSeconds(60));

        NacosWarmupHelper.WarmupStatus status = NacosWarmupHelper.calculateWarmupStatus(metadataProvider, properties);

        assertThat(status.warmupDuration()).isEqualTo("60s");
        assertThat(status.warmupUptimeSeconds()).isGreaterThanOrEqualTo(20L);
        assertThat(status.warmupRemainingSeconds()).isLessThanOrEqualTo(40L);
        assertThat(status.warmupCompleted()).isFalse();
    }

    @Test
    @DisplayName("预热状态计算：已超过预热窗口时，剩余倒计时为 0 且完成态为 true")
    void testCalculateWarmupStatusCompleted() {
        long now = System.currentTimeMillis();
        long startupTime = now - 70_000L; // 启动了 70 秒，预热 60 秒

        NacosMetadataProvider metadataProvider = mock(NacosMetadataProvider.class);
        when(metadataProvider.getMetadata()).thenReturn(Map.of(
                DefaultNacosMetadataProvider.KEY_STARTUP_TIME, String.valueOf(startupTime),
                DefaultNacosMetadataProvider.KEY_WARMUP_DURATION, "60s"
        ));

        AtengNacosProperties properties = new AtengNacosProperties();

        NacosWarmupHelper.WarmupStatus status = NacosWarmupHelper.calculateWarmupStatus(metadataProvider, properties);

        assertThat(status.warmupDuration()).isEqualTo("60s");
        assertThat(status.warmupUptimeSeconds()).isGreaterThanOrEqualTo(70L);
        assertThat(status.warmupRemainingSeconds()).isEqualTo(0L);
        assertThat(status.warmupCompleted()).isTrue();
    }

    @Test
    @DisplayName("预热状态计算：metadataProvider 为空时自适应回退到 JVM 启动时间与 properties")
    void testCalculateWarmupStatusFallback() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getMetadata().setWarmupDuration(Duration.ofSeconds(120));

        NacosWarmupHelper.WarmupStatus status = NacosWarmupHelper.calculateWarmupStatus(null, properties);

        assertThat(status.warmupDuration()).isEqualTo("120s");
        assertThat(status.warmupUptimeSeconds()).isGreaterThanOrEqualTo(0L);
        assertThat(status.warmupRemainingSeconds()).isGreaterThan(0L);
    }
}
