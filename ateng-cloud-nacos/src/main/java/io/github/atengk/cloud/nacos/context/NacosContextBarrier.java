package io.github.atengk.cloud.nacos.context;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Nacos 三元上下文隔离屏障 (Context Barrier)
 *
 * <p>负责在异步回调（配置监听、实例订阅）与动态配置热更执行边界，
 * 建立针对 &lt;MDC traceId, UserContextHolder, TenantContextHolder&gt; 的快照保存与确定性恢复/清理屏障，
 * 杜绝后台 Worker 线程池上下文污染，并维持已有业务线程上下文不被破坏。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
public final class NacosContextBarrier {

    public static final String MDC_TRACE_ID_KEY = "traceId";

    private NacosContextBarrier() {
    }

    /**
     * 在三元上下文隔离屏障中执行无返回值任务
     *
     * @param action 待执行的任务
     */
    public static void run(Runnable action) {
        run(null, action);
    }

    /**
     * 在三元上下文隔离屏障中执行无返回值任务（支持指定 traceId）
     *
     * @param customTraceId 指定的 traceId (若为空则沿用已有或自动生成)
     * @param action        待执行的任务
     */
    public static void run(@Nullable String customTraceId, Runnable action) {
        if (action == null) {
            return;
        }
        call(customTraceId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 在三元上下文隔离屏障中执行带返回值任务
     *
     * @param action 待执行的任务
     * @param <T>    返回值类型
     * @return 任务返回值
     */
    public static <T> T call(Callable<T> action) {
        return call(null, action);
    }

    /**
     * 在三元上下文隔离屏障中执行带返回值任务（支持指定 traceId）
     *
     * @param customTraceId 指定的 traceId (若为空则沿用已有或自动生成)
     * @param action        待执行的任务
     * @param <T>           返回值类型
     * @return 任务返回值
     */
    public static <T> T call(@Nullable String customTraceId, Callable<T> action) {
        if (action == null) {
            return null;
        }
        return callWithTrace(customTraceId, traceId -> {
            try {
                return action.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("NacosContextBarrier action execution failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 在三元上下文隔离屏障中执行任务并将当前激活的 traceId 透传给回调
     *
     * @param action 接收当前激活 traceId 的任务函数
     * @param <T>    返回值类型
     * @return 任务返回值
     */
    public static <T> T callWithTrace(Function<String, T> action) {
        return callWithTrace(null, action);
    }

    /**
     * 在三元上下文隔离屏障中执行任务并将当前激活的 traceId 透传给回调（支持指定 traceId）
     *
     * @param customTraceId 指定的 traceId (若为空则沿用已有或自动生成)
     * @param action        接收当前激活 traceId 的任务函数
     * @param <T>           返回值类型
     * @return 任务返回值
     */
    public static <T> T callWithTrace(@Nullable String customTraceId, Function<String, T> action) {
        if (action == null) {
            return null;
        }

        // 1. 先完整捕获当前线程的三元上下文快照，确保任何阶段异常都能在 finally 对称恢复
        String existingTraceId = MDC.get(MDC_TRACE_ID_KEY);
        String existingUserId = UserContextHolder.getUserId();
        TenantSnapshot tenantSnapshot = TenantContextHolder.capture();

        String activeTraceId = StringUtils.hasText(customTraceId)
                ? customTraceId
                : (StringUtils.hasText(existingTraceId) ? existingTraceId : IdUtil.simpleUUID());

        try {
            if (StringUtils.hasText(customTraceId) || !StringUtils.hasText(existingTraceId)) {
                MDC.put(MDC_TRACE_ID_KEY, activeTraceId);
            }
            return action.apply(activeTraceId);
        } finally {
            // 2. 确定性恢复或清理上下文快照
            // 2.1 恢复或清理 MDC traceId
            if (existingTraceId != null) {
                MDC.put(MDC_TRACE_ID_KEY, existingTraceId);
            } else {
                MDC.remove(MDC_TRACE_ID_KEY);
            }

            // 2.2 恢复或清理 UserContextHolder
            if (existingUserId != null) {
                UserContextHolder.setUserId(existingUserId);
            } else {
                UserContextHolder.clear();
            }

            // 2.3 恢复或清理 TenantContextHolder
            if (tenantSnapshot != null) {
                TenantContextHolder.restore(tenantSnapshot);
            } else {
                TenantContextHolder.clear();
            }
        }
    }
}
