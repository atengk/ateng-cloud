package io.github.atengk.cloud.sample.nacos.config;

import io.github.atengk.cloud.nacos.event.SafeConfigRefreshEvent;
import io.github.atengk.cloud.nacos.spi.NacosConfigChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 示例 Nacos 配置监听器 SPI 实现
 *
 * <p>演示按指定前缀（{@code demo.config}）接收配置变更事件并执行业务逻辑。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Component
public class DemoNacosConfigChangeListener implements NacosConfigChangeListener {

    private static final Logger log = LoggerFactory.getLogger(DemoNacosConfigChangeListener.class);

    private final AtomicInteger changeCount = new AtomicInteger();
    private volatile SafeConfigRefreshEvent lastEvent;

    @Override
    public void onConfigChange(SafeConfigRefreshEvent event) {
        changeCount.incrementAndGet();
        this.lastEvent = event;
        log.info("[DemoNacosConfigChangeListener] 收到前缀 'demo.config' 变更通知，traceId: {}, changedKeys: {}",
                event.getTraceId(), event.getChangedKeys());
    }

    @Override
    public String getPrefix() {
        return "demo.config";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    public int getChangeCount() {
        return changeCount.get();
    }

    public SafeConfigRefreshEvent getLastEvent() {
        return lastEvent;
    }

    public void reset() {
        changeCount.set(0);
        lastEvent = null;
    }
}
