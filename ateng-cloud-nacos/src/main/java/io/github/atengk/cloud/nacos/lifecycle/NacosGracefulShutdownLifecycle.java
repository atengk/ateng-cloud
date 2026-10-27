package io.github.atengk.cloud.nacos.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Nacos 优雅无损下线 Spring 生命周期钩子
 *
 * <p>以最高优先级（{@link Integer#MAX_VALUE}）编排停机时序，在 Web 容器与连接池销毁前，
 * 优先执行实例主动注销与在途请求排空。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class NacosGracefulShutdownLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NacosGracefulShutdownLifecycle.class);

    private final NacosLifecycleStateMachine stateMachine;
    private volatile boolean running = false;

    public NacosGracefulShutdownLifecycle(NacosLifecycleStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @Override
    public void start() {
        this.running = true;
        log.info("Nacos graceful shutdown lifecycle hook registered with highest priority (phase={}).", getPhase());
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            if (callback != null) {
                callback.run();
            }
        }
    }

    @Override
    public void stop() {
        if (this.running) {
            log.info("Spring ApplicationContext shutdown detected, executing Nacos graceful deregistration & draining...");
            stateMachine.markOffline();
            this.running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public int getPhase() {
        // 最高优先级，确保在 Web 容器与数据源连接池关闭之前最先执行
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
