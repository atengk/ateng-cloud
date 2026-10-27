package io.github.atengk.cloud.nacos.lifecycle;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.cloud.client.serviceregistry.AbstractAutoServiceRegistration;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Nacos 微服务生命周期状态机
 *
 * <p>以原子状态驱动双阶段优雅下线排空与上线恢复，联动 Spring Boot 探针及 Nacos 注册表。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class NacosLifecycleStateMachine {

    private static final Logger log = LoggerFactory.getLogger(NacosLifecycleStateMachine.class);

    private final AtengNacosProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    @SuppressWarnings("rawtypes")
    private final ObjectProvider<AbstractAutoServiceRegistration> autoRegistrationProvider;

    private final AtomicReference<NacosInstanceLifecycleState> state =
            new AtomicReference<>(NacosInstanceLifecycleState.UP);

    @SuppressWarnings("rawtypes")
    public NacosLifecycleStateMachine(AtengNacosProperties properties,
                                      ApplicationEventPublisher eventPublisher,
                                      ObjectProvider<AbstractAutoServiceRegistration> autoRegistrationProvider) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.autoRegistrationProvider = autoRegistrationProvider;
    }

    /**
     * 获取当前生命周期状态
     */
    public NacosInstanceLifecycleState getState() {
        return state.get();
    }

    /**
     * 判断当前是否处于排空状态中
     */
    public boolean isDraining() {
        return state.get() == NacosInstanceLifecycleState.DRAINING;
    }

    /**
     * 触发优雅注销与在途请求排空（双阶段状态机）
     *
     * @return 若成功触发排空返回 true；若已处于排空或下线状态则幂等返回 false（避免重复睡眠）
     */
    public boolean markOffline() {
        // 原子防重：仅允许从 UP 状态切换为 DRAINING
        if (!state.compareAndSet(NacosInstanceLifecycleState.UP, NacosInstanceLifecycleState.DRAINING)) {
            log.warn("Nacos instance is already in '{}' state, skipping redundant offline draining.", state.get());
            return false;
        }

        log.info("Starting Nacos graceful deregistration & in-flight draining sequence...");

        // 阶段一：联动就绪探针拒绝新流量接入 + 主动向 Nacos 撤销实例注册
        notifyReadiness(ReadinessState.REFUSING_TRAFFIC);
        deregisterFromNacos();

        // 阶段二：阻塞等待在途请求排空安全窗口
        Duration drainTimeout = properties.getDrainTimeout();
        if (drainTimeout != null && !drainTimeout.isZero() && !drainTimeout.isNegative()) {
            long millis = drainTimeout.toMillis();
            log.info("Suspending current thread for in-flight requests draining window: {} ms...", millis);
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Nacos in-flight draining window was interrupted: {}", e.getMessage());
            }
        }

        state.set(NacosInstanceLifecycleState.OUT_OF_SERVICE);
        log.info("Nacos graceful deregistration & draining completed. Instance is now OUT_OF_SERVICE.");
        return true;
    }

    /**
     * 触发重新挂牌上线
     *
     * @return 若成功恢复上线返回 true；若已是在线状态返回 false
     */
    public boolean markOnline() {
        if (!state.compareAndSet(NacosInstanceLifecycleState.OUT_OF_SERVICE, NacosInstanceLifecycleState.UP)) {
            log.warn("Nacos instance cannot be marked online from current state: {}", state.get());
            return false;
        }

        log.info("Restoring Nacos instance to online state...");
        registerToNacos();
        notifyReadiness(ReadinessState.ACCEPTING_TRAFFIC);

        log.info("Nacos instance successfully restored to UP state.");
        return true;
    }

    private void notifyReadiness(ReadinessState readinessState) {
        if (eventPublisher != null) {
            try {
                AvailabilityChangeEvent.publish(eventPublisher, this, readinessState);
                log.info("Spring Boot ReadinessState switched to: {}", readinessState);
            } catch (Exception ex) {
                log.warn("Failed to publish AvailabilityChangeEvent for {}: {}", readinessState, ex.getMessage());
            }
        }
    }

    private void deregisterFromNacos() {
        try {
            AbstractAutoServiceRegistration<?> registration = autoRegistrationProvider.getIfAvailable();
            if (registration != null && registration.isRunning()) {
                log.info("Invoking AbstractAutoServiceRegistration.stop() to deregister instance from Nacos...");
                registration.stop();
            } else {
                log.debug("No active AbstractAutoServiceRegistration found for deregistration (possibly offline mode).");
            }
        } catch (Exception ex) {
            log.error("Failed to deregister instance from Nacos during graceful shutdown: {}", ex.getMessage(), ex);
        }
    }

    private void registerToNacos() {
        try {
            AbstractAutoServiceRegistration<?> registration = autoRegistrationProvider.getIfAvailable();
            if (registration != null && !registration.isRunning()) {
                log.info("Invoking AbstractAutoServiceRegistration.start() to register instance back to Nacos...");
                registration.start();
            }
        } catch (Exception ex) {
            log.error("Failed to re-register instance to Nacos: {}", ex.getMessage(), ex);
        }
    }
}
