package io.github.atengk.cloud.traffic.context;

import io.github.atengk.boot.core.context.AsyncContextSnapshotProvider;

/**
 * 流量上下文异步任务快照提供者
 *
 * <p>深度接入 {@code ateng-boot-core} 的异步线程池装饰器设施 ({@code ContextCopyingTaskDecorator})，
 * 在向受管线程池提交异步任务时自动快照当前流量上下文，在工作线程中无缝恢复，并在任务执行完毕后安全清理与还原。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficContextSnapshotProvider implements AsyncContextSnapshotProvider {

    @Override
    public Object capture() {
        return TrafficContextHolder.getContext();
    }

    @Override
    public void apply(Object snapshot) {
        if (snapshot instanceof TrafficContext context) {
            TrafficContextHolder.setContext(context);
        } else {
            TrafficContextHolder.clear();
        }
    }

    @Override
    public void restore(Object previous) {
        if (previous instanceof TrafficContext context) {
            TrafficContextHolder.setContext(context);
        } else {
            TrafficContextHolder.clear();
        }
    }
}
