package io.github.atengk.cloud.nacos.metadata;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.lang.management.ManagementFactory;
import java.util.Map;

/**
 * Nacos 实例启动预热状态与倒计时辅助计算器
 *
 * <p>集中计算微服务节点的启动运行时间、预热窗口时长、剩余倒计时与预热完成状态，
 * 供 Actuator 运维端点、全景健康诊断器与 Micrometer 监控指标统一复用。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public final class NacosWarmupHelper {

    private NacosWarmupHelper() {
    }

    /**
     * 计算当前节点的预热运行状态
     *
     * @param metadataProvider 实例元数据提供者
     * @param properties       Nacos 治理配置属性
     * @return 预热运行状态结构化快照
     */
    public static WarmupStatus calculateWarmupStatus(
            @Nullable NacosMetadataProvider metadataProvider,
            @Nullable AtengNacosProperties properties) {
        long now = System.currentTimeMillis();
        long startupTime = 0L;
        String warmupDurationStr = null;

        if (metadataProvider != null) {
            Map<String, String> meta = metadataProvider.getMetadata();
            if (meta != null) {
                String startupTimeMeta = meta.get(DefaultNacosMetadataProvider.KEY_STARTUP_TIME);
                if (StringUtils.hasText(startupTimeMeta)) {
                    try {
                        startupTime = Long.parseLong(startupTimeMeta.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                warmupDurationStr = meta.get(DefaultNacosMetadataProvider.KEY_WARMUP_DURATION);
            }
        }

        if (startupTime <= 0) {
            try {
                startupTime = ManagementFactory.getRuntimeMXBean().getStartTime();
            } catch (Exception ignored) {
                startupTime = now;
            }
        }

        long defaultSeconds = 60L;
        if (properties != null && properties.getMetadata() != null && properties.getMetadata().getWarmupDuration() != null) {
            defaultSeconds = Math.max(0L, properties.getMetadata().getWarmupDuration().getSeconds());
        }

        long warmupDurationSeconds;
        if (StringUtils.hasText(warmupDurationStr)) {
            warmupDurationSeconds = parseDurationToSeconds(warmupDurationStr, defaultSeconds);
        } else {
            warmupDurationSeconds = defaultSeconds;
        }

        long uptimeSeconds = Math.max(0L, (now - startupTime) / 1000L);
        long remainingSeconds = Math.max(0L, warmupDurationSeconds - uptimeSeconds);
        boolean completed = uptimeSeconds >= warmupDurationSeconds;

        String durationDisplay = StringUtils.hasText(warmupDurationStr)
                ? warmupDurationStr : (warmupDurationSeconds + "s");

        return new WarmupStatus(durationDisplay, uptimeSeconds, remainingSeconds, completed);
    }

    /**
     * 将预热时长字符串解析为秒数
     *
     * @param durationStr    格式如 "60s" 或 "60" 的时长字符串
     * @param defaultSeconds 解析失败时的保底秒数
     * @return 解析得到的有效秒数
     */
    public static long parseDurationToSeconds(@Nullable String durationStr, long defaultSeconds) {
        if (!StringUtils.hasText(durationStr)) {
            return defaultSeconds;
        }
        String cleanDurationStr = durationStr.trim().toLowerCase();
        if (cleanDurationStr.endsWith("s")) {
            cleanDurationStr = cleanDurationStr.substring(0, cleanDurationStr.length() - 1);
        }
        try {
            long sec = Long.parseLong(cleanDurationStr);
            return sec >= 0 ? sec : defaultSeconds;
        } catch (NumberFormatException e) {
            return defaultSeconds;
        }
    }

    /**
     * 预热状态结构化只读快照
     *
     * @param warmupDuration         预热窗口时长显示文本（如 "60s"）
     * @param warmupUptimeSeconds    自节点启动以来的运行秒数
     * @param warmupRemainingSeconds 预热窗口剩余秒数（预热完成后为 0）
     * @param warmupCompleted        预热是否已完成
     */
    public record WarmupStatus(
            String warmupDuration,
            long warmupUptimeSeconds,
            long warmupRemainingSeconds,
            boolean warmupCompleted
    ) {
    }
}
