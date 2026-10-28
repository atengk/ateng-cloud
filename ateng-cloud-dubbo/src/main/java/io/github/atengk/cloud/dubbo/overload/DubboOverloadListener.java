package io.github.atengk.cloud.dubbo.overload;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.threadpool.event.ThreadPoolExhaustedEvent;
import org.apache.dubbo.common.threadpool.event.ThreadPoolExhaustedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Dubbo 服务端线程池过载耗尽监听器 (Dubbo SPI)
 * <p>
 * 当 Dubbo 线程池耗尽触发拒绝策略时，Dubbo 框架底层通过 SPI 派发 {@link ThreadPoolExhaustedEvent}。
 * 本监听器捕获事件并执行：
 * 1. 打印详细过载诊断日志；
 * 2. 递增全局拒绝计数器，供 Micrometer 监控采集与告警大盘展示；
 * 3. 记录最近一次过载时间戳，辅助故障排查定位。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate
public class DubboOverloadListener implements ThreadPoolExhaustedListener {

    private static final Logger log = LoggerFactory.getLogger(DubboOverloadListener.class);

    private static final AtomicLong REJECTED_COUNT = new AtomicLong(0);

    private static volatile long lastExhaustedTimestamp = 0L;

    @Override
    public void onEvent(ThreadPoolExhaustedEvent event) {
        long currentCount = REJECTED_COUNT.incrementAndGet();
        lastExhaustedTimestamp = System.currentTimeMillis();
        String msg = event != null ? event.getMsg() : "Unknown thread pool exhaustion";
        log.error("[DUBBO-OVERLOAD] Provider 线程池过载耗尽告警 (总拒绝次数={}): {}", currentCount, msg);
    }

    /**
     * 递增全局拒绝计数
     *
     * @return 递增后的总数
     */
    public static long incrementRejectCount() {
        lastExhaustedTimestamp = System.currentTimeMillis();
        return REJECTED_COUNT.incrementAndGet();
    }

    /**
     * 获取全局线程池拒绝总数
     *
     * @return 拒绝总数
     */
    public static long getRejectedCount() {
        return REJECTED_COUNT.get();
    }

    /**
     * 获取最近一次线程池耗尽发生时间戳 (毫秒)
     *
     * @return 时间戳
     */
    public static long getLastExhaustedTimestamp() {
        return lastExhaustedTimestamp;
    }

    /**
     * 重置计数器 (主要供测试用例自洽清理)
     */
    public static void reset() {
        REJECTED_COUNT.set(0);
        lastExhaustedTimestamp = 0L;
    }
}
