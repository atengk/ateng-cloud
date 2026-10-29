package io.github.atengk.cloud.traffic.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * 流量染色与泳道状态线程上下文持有者
 *
 * <p>核心治理语义：
 * 1. 基于 {@link TransmittableThreadLocal} 存储当前线程的流量染色上下文，支持异步多线程池穿透；
 * 2. 具备与 SLF4J MDC 双向联动机制：写入/清理流量标签时自动同步至 MDC 键 {@code X-Traffic-Tag}；
 * 3. 具备 MDC 兜底读取防线：当 TTL 缺失但 MDC 存在标签时自动平滑回填，保障日志链路与业务路由一致性；
 * 4. 严守确定性生命周期契约：提供 {@link #clear()} 接口，在拦截器出口的 {@code finally} 块中幂等清理，杜绝线程污染。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public final class TrafficContextHolder {

    private static final TransmittableThreadLocal<TrafficContext> CONTEXT_HOLDER = new TransmittableThreadLocal<>();

    private TrafficContextHolder() {
    }

    /**
     * 设置完整的流量染色上下文，并同步至 MDC
     *
     * @param context 流量上下文，为 null 时自动执行清理
     */
    public static void setContext(TrafficContext context) {
        if (context == null || !context.hasTag()) {
            clear();
            return;
        }
        CONTEXT_HOLDER.set(context);
        MDC.put(TrafficConstants.MDC_TRAFFIC_TAG, context.getTag());
    }

    /**
     * 便捷设置流量标签，并同步至 MDC
     *
     * @param tag 流量标签
     */
    public static void setTag(String tag) {
        if (!StringUtils.hasText(tag)) {
            clear();
            return;
        }
        setContext(TrafficContext.of(tag));
    }

    /**
     * 获取当前线程的流量染色上下文
     *
     * <p>优先读取 TTL；若 TTL 为空则探测 SLF4J MDC 进行平滑对齐。</p>
     *
     * @return 当前流量上下文，无标签时返回 null
     */
    public static TrafficContext getContext() {
        TrafficContext context = CONTEXT_HOLDER.get();
        if (context != null && context.hasTag()) {
            return context;
        }
        // MDC 兜底对齐
        String mdcTag = MDC.get(TrafficConstants.MDC_TRAFFIC_TAG);
        if (StringUtils.hasText(mdcTag)) {
            String trimmedTag = mdcTag.trim();
            TrafficContext fallbackContext;
            if (context != null) {
                fallbackContext = new TrafficContext(trimmedTag, context.getLaneName(), context.getMatchedRule(), context.getMetadata());
            } else {
                fallbackContext = TrafficContext.of(trimmedTag);
            }
            CONTEXT_HOLDER.set(fallbackContext);
            return fallbackContext;
        }
        return null;
    }

    /**
     * 获取当前流量染色标签
     *
     * @return 流量标签字符串，无标签时返回 null
     */
    public static String getTag() {
        TrafficContext context = getContext();
        return context != null ? context.getTag() : null;
    }

    /**
     * 清理当前线程的流量上下文与 MDC 标记
     */
    public static void clear() {
        CONTEXT_HOLDER.remove();
        MDC.remove(TrafficConstants.MDC_TRAFFIC_TAG);
    }

    /**
     * 便捷别名清理接口
     */
    public static void remove() {
        clear();
    }

    /**
     * 在指定的流量上下文作用域下安全执行代码块，执行完毕后自动恢复先前的上下文
     *
     * @param context  作用域生效的完整流量上下文
     * @param runnable 执行任务
     */
    public static void runWithContext(TrafficContext context, Runnable runnable) {
        runWithContext(context, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 在指定的流量上下文作用域下安全执行带返回值的函数，执行完毕后自动恢复先前的上下文
     *
     * @param context  作用域生效的完整流量上下文
     * @param supplier 执行函数
     * @param <T>      返回值类型
     * @return 函数返回值
     */
    public static <T> T runWithContext(TrafficContext context, Supplier<T> supplier) {
        TrafficContext previous = CONTEXT_HOLDER.get();
        String previousMdc = MDC.get(TrafficConstants.MDC_TRAFFIC_TAG);
        try {
            setContext(context);
            return supplier.get();
        } finally {
            if (previous != null) {
                setContext(previous);
            } else {
                clear();
            }
            if (previousMdc != null) {
                MDC.put(TrafficConstants.MDC_TRAFFIC_TAG, previousMdc);
            }
        }
    }

    /**
     * 在指定的流量标签作用域下安全执行代码块，执行完毕后自动恢复先前的上下文
     *
     * @param tag      作用域生效的流量标签
     * @param runnable 执行任务
     */
    public static void runWithTag(String tag, Runnable runnable) {
        runWithContext(StringUtils.hasText(tag) ? TrafficContext.of(tag.trim()) : null, runnable);
    }

    /**
     * 在指定的流量标签作用域下安全执行带返回值的函数，执行完毕后自动恢复先前的上下文
     *
     * @param tag      作用域生效的流量标签
     * @param supplier 执行函数
     * @param <T>      返回值类型
     * @return 函数返回值
     */
    public static <T> T runWithTag(String tag, Supplier<T> supplier) {
        return runWithContext(StringUtils.hasText(tag) ? TrafficContext.of(tag.trim()) : null, supplier);
    }
}
