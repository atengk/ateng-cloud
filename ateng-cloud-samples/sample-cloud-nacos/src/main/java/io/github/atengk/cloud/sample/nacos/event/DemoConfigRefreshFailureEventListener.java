package io.github.atengk.cloud.sample.nacos.event;

import io.github.atengk.cloud.nacos.event.SafeConfigRefreshFailureEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Nacos 配置安全热更失败领域事件监听器示例
 *
 * <p>实时监听 {@link SafeConfigRefreshFailureEvent} 领域事件，
 * 演示在配置热更重绑或监听器异常时无缝接收失败根因、受影响属性键集与链路 traceId，
 * 用于驱动企业级故障告警通知（如钉钉/企业微信机器人）与应急联动。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Component
public class DemoConfigRefreshFailureEventListener {

    private static final Logger log = LoggerFactory.getLogger(DemoConfigRefreshFailureEventListener.class);

    private final AtomicInteger eventCount = new AtomicInteger(0);
    private final AtomicReference<SafeConfigRefreshFailureEvent> lastEvent = new AtomicReference<>();

    @EventListener
    public void onSafeConfigRefreshFailure(SafeConfigRefreshFailureEvent event) {
        int count = eventCount.incrementAndGet();
        lastEvent.set(event);
        log.warn("[告警通知] 检测到 Nacos 配置热更异常领域事件 #{} [dataId='{}', group='{}', changedKeys={}, traceId='{}', error='{}']",
                count, event.getDataId(), event.getGroup(), event.getChangedKeys(), event.getTraceId(), event.getErrorMessage());
    }

    public int getEventCount() {
        return eventCount.get();
    }

    public SafeConfigRefreshFailureEvent getLastEvent() {
        return lastEvent.get();
    }

    public void reset() {
        eventCount.set(0);
        lastEvent.set(null);
    }
}
