package io.github.atengk.cloud.dubbo.overload;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.store.DataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dubbo 核心线程池状态与过载拒绝指标绑定器 (Micrometer)
 * <p>
 * 对接 Micrometer 观测体系：
 * 1. 导出 Dubbo 服务端线程池过载拒绝累计计数器 (dubbo.threadpool.reject)；
 * 2. 导出 Dubbo 线程池运行时指标：活跃线程数 (active)、当前线程数 (size)、核心线程数 (core)、最大线程数 (max)、队列积压数 (queue.size) 与已完成任务数 (completed)。
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class DubboOverloadMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(DubboOverloadMetrics.class);

    private final Map<String, ThreadPoolExecutor> registeredExecutors = new ConcurrentHashMap<>();

    @Override
    public void bindTo(MeterRegistry registry) {
        if (registry == null) {
            return;
        }

        // 1. 注册全局线程池过载拒绝总计数器
        FunctionCounter.builder("dubbo.threadpool.reject", this, metrics -> DubboOverloadListener.getRejectedCount())
                .description("Dubbo 线程池触发过载保护拒绝任务的总累计计数")
                .register(registry);

        // 2. 动态扫描并绑定 Dubbo 内部线程池执行器
        bindDubboExecutors(registry);
    }

    /**
     * 动态探测并向注册表绑定 Dubbo 线程池指标
     *
     * @param registry Micrometer 度量注册表
     */
    public void bindDubboExecutors(MeterRegistry registry) {
        try {
            DataStore dataStore = ExtensionLoader.getExtensionLoader(DataStore.class).getDefaultExtension();
            if (dataStore == null) {
                return;
            }
            Map<String, Object> executors = dataStore.get(CommonConstants.EXECUTOR_SERVICE_COMPONENT_KEY);
            if (executors == null || executors.isEmpty()) {
                return;
            }

            for (Map.Entry<String, Object> entry : executors.entrySet()) {
                String poolPortOrName = entry.getKey();
                Object executorObj = entry.getValue();
                if (executorObj instanceof ThreadPoolExecutor executor) {
                    if (!registeredExecutors.containsKey(poolPortOrName)) {
                        registeredExecutors.put(poolPortOrName, executor);
                        registerExecutorMetrics(registry, poolPortOrName, executor);
                    }
                }
            }
        } catch (Throwable t) {
            log.debug("[DUBBO-METRICS] 探测绑定 Dubbo 线程池指标提示: {}", t.getMessage());
        }
    }

    /**
     * 手动显式注册一个受管线程池至 Micrometer (支持测试或自定义线程池挂载)
     *
     * @param registry 度量注册表
     * @param poolName 线程池标识名
     * @param executor 线程池对象
     */
    public void registerCustomExecutor(MeterRegistry registry, String poolName, ThreadPoolExecutor executor) {
        if (registry == null || executor == null) {
            return;
        }
        String name = poolName != null ? poolName : "custom";
        if (!registeredExecutors.containsKey(name)) {
            registeredExecutors.put(name, executor);
            registerExecutorMetrics(registry, name, executor);
        }
    }

    private void registerExecutorMetrics(MeterRegistry registry, String poolName, ThreadPoolExecutor executor) {
        Iterable<Tag> tags = Tags.of("pool", poolName);

        Gauge.builder("dubbo.threadpool.active", executor, ThreadPoolExecutor::getActiveCount)
                .tags(tags)
                .description("Dubbo 线程池当前活跃线程数")
                .register(registry);

        Gauge.builder("dubbo.threadpool.size", executor, ThreadPoolExecutor::getPoolSize)
                .tags(tags)
                .description("Dubbo 线程池当前物理线程数")
                .register(registry);

        Gauge.builder("dubbo.threadpool.core", executor, ThreadPoolExecutor::getCorePoolSize)
                .tags(tags)
                .description("Dubbo 线程池核心线程数")
                .register(registry);

        Gauge.builder("dubbo.threadpool.max", executor, ThreadPoolExecutor::getMaximumPoolSize)
                .tags(tags)
                .description("Dubbo 线程池最大线程数")
                .register(registry);

        Gauge.builder("dubbo.threadpool.queue.size", executor, e -> e.getQueue() != null ? e.getQueue().size() : 0)
                .tags(tags)
                .description("Dubbo 线程池工作队列当前积压任务数")
                .register(registry);

        Gauge.builder("dubbo.threadpool.completed", executor, ThreadPoolExecutor::getCompletedTaskCount)
                .tags(tags)
                .description("Dubbo 线程池已完成任务总数")
                .register(registry);

        log.info("[DUBBO-METRICS] 成功绑定 Dubbo 线程池指标监控: pool={}", poolName);
    }
}
