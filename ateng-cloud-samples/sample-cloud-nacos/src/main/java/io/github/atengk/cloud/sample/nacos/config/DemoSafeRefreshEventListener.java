package io.github.atengk.cloud.sample.nacos.config;

import io.github.atengk.cloud.nacos.event.SafeConfigRefreshEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spring 标准事件监听器示例
 *
 * <p>演示使用 Spring {@link EventListener} 监听 {@link SafeConfigRefreshEvent} 强类型事件。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Component
public class DemoSafeRefreshEventListener {

    private static final Logger log = LoggerFactory.getLogger(DemoSafeRefreshEventListener.class);

    private final AtomicInteger eventCount = new AtomicInteger();
    private volatile SafeConfigRefreshEvent lastEvent;

    @EventListener
    public void onSafeRefresh(SafeConfigRefreshEvent event) {
        eventCount.incrementAndGet();
        this.lastEvent = event;
        log.info("[DemoSafeRefreshEventListener] @EventListener 收到热更事件，traceId: {}, changedCount: {}",
                event.getTraceId(), event.getChangedKeys().size());
    }

    public int getEventCount() {
        return eventCount.get();
    }

    public SafeConfigRefreshEvent getLastEvent() {
        return lastEvent;
    }

    public void reset() {
        eventCount.set(0);
        lastEvent = null;
    }
}
