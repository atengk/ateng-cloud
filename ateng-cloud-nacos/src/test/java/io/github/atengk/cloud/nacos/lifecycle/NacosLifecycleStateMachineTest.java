package io.github.atengk.cloud.nacos.lifecycle;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.cloud.client.serviceregistry.AbstractAutoServiceRegistration;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生命周期状态机与双阶段优雅下线单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class NacosLifecycleStateMachineTest {

    @Test
    @DisplayName("测试状态机优雅下线与幂等防重流转")
    void testMarkOfflineAndIdempotency() {
        AtengNacosProperties properties = new AtengNacosProperties();
        // 设置极短排空时间，保障单测秒级执行
        properties.setDrainTimeout(Duration.ofMillis(20));

        List<ApplicationEvent> publishedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof ApplicationEvent ae) {
                publishedEvents.add(ae);
            }
        };

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        NacosLifecycleStateMachine stateMachine = new NacosLifecycleStateMachine(
                properties, publisher, beanFactory.getBeanProvider(AbstractAutoServiceRegistration.class)
        );

        // 1. 初始状态为 UP
        assertThat(stateMachine.getState()).isEqualTo(NacosInstanceLifecycleState.UP);
        assertThat(stateMachine.isDraining()).isFalse();

        // 2. 首次下线：成功触发排空
        boolean firstResult = stateMachine.markOffline();
        assertThat(firstResult).isTrue();
        assertThat(stateMachine.getState()).isEqualTo(NacosInstanceLifecycleState.OUT_OF_SERVICE);

        // 验证探针切换事件被正确发布
        assertThat(publishedEvents)
                .anyMatch(e -> e instanceof AvailabilityChangeEvent<?> ace && ace.getState() == ReadinessState.REFUSING_TRAFFIC);

        // 3. 重复下线：幂等返回 false，绝不重复睡眠
        boolean secondResult = stateMachine.markOffline();
        assertThat(secondResult).isFalse();
        assertThat(stateMachine.getState()).isEqualTo(NacosInstanceLifecycleState.OUT_OF_SERVICE);

        // 4. 恢复上线：成功恢复为 UP
        boolean onlineResult = stateMachine.markOnline();
        assertThat(onlineResult).isTrue();
        assertThat(stateMachine.getState()).isEqualTo(NacosInstanceLifecycleState.UP);

        assertThat(publishedEvents)
                .anyMatch(e -> e instanceof AvailabilityChangeEvent<?> ace && ace.getState() == ReadinessState.ACCEPTING_TRAFFIC);
    }
}
