package io.github.atengk.cloud.dubbo.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.util.StringUtils;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Dubbo RPC 慢调用 Micrometer 指标绑定与采集器
 * <p>
 * 对接 Micrometer 观测体系，导出 {@code dubbo.rpc.slow.calls} 计数器，并附带
 * {@code side}、{@code service}、{@code method} 维度标签，在未引入 Micrometer 或未绑定注册表时安全静默降级。
 *
 * @author Ateng
 * @since 2026-11-12
 */
public class DubboSlowCallMetrics implements MeterBinder {

    /**
     * Dubbo RPC 慢调用计数指标名
     */
    public static final String METRIC_SLOW_CALLS = "dubbo.rpc.slow.calls";

    private static final AtomicReference<MeterRegistry> REGISTRY_REF = new AtomicReference<>();

    @Override
    public void bindTo(MeterRegistry registry) {
        if (registry != null) {
            REGISTRY_REF.set(registry);
        }
    }

    /**
     * 累加一次 Dubbo RPC 慢调用计数
     *
     * @param side        调用角色 ({@code consumer} 或 {@code provider})
     * @param serviceName 接口全限定名
     * @param methodName  调用方法名
     */
    public static void recordSlowCall(String side, String serviceName, String methodName) {
        MeterRegistry registry = REGISTRY_REF.get();
        if (registry == null) {
            return;
        }
        Counter.builder(METRIC_SLOW_CALLS)
                .description("Dubbo RPC 慢调用累计次数")
                .tag("side", normalizeTag(side))
                .tag("service", normalizeTag(serviceName))
                .tag("method", normalizeTag(methodName))
                .register(registry)
                .increment();
    }

    /**
     * 重置已绑定的 Micrometer 注册表引用（供离线单元测试隔离清理使用）
     */
    public static void reset() {
        REGISTRY_REF.set(null);
    }

    private static String normalizeTag(String value) {
        return StringUtils.hasText(value) ? value.trim() : "unknown";
    }
}
