package io.github.atengk.cloud.sample.nacos.event;

import io.github.atengk.cloud.nacos.event.NacosConnectionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Nacos gRPC 长连接生命周期领域事件监听器演示
 *
 * <p>实时监听 {@link NacosConnectionEvent} 领域事件，感知底层通信闪断与重连恢复。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Component
public class DemoConnectionEventListener {

    private static final Logger log = LoggerFactory.getLogger(DemoConnectionEventListener.class);

    private final AtomicInteger eventCount = new AtomicInteger(0);
    private final AtomicReference<NacosConnectionEvent> lastEvent = new AtomicReference<>();

    @EventListener
    public void onNacosConnectionEvent(NacosConnectionEvent event) {
        int count = eventCount.incrementAndGet();
        lastEvent.set(event);
        log.info("[DemoConnectionEventListener] 监听到 Nacos 长连接事件 #{} [module={}, status={}, server={}]",
                count, event.getModuleType(), event.getStatus(), event.getServerAddress());
    }

    public int getEventCount() {
        return eventCount.get();
    }

    public NacosConnectionEvent getLastEvent() {
        return lastEvent.get();
    }
}
