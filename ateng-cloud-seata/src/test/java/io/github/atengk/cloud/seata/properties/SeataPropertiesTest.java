package io.github.atengk.cloud.seata.properties;

import io.github.atengk.cloud.seata.autoconfigure.AtengSeataAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分布式事务增强配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class SeataPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSeataAutoConfiguration.class));

    @Test
    @DisplayName("测试配置属性类默认值符合高并发生产规范")
    void testDefaultValues() {
        SeataProperties properties = new SeataProperties();
        assertThat(properties.isEnabled()).isTrue();

        // 默认事务超时 30 秒
        assertThat(properties.getTx().getDefaultTimeout()).isEqualTo(Duration.ofSeconds(30));

        // 默认锁重试间隔 50ms，重试 30 次
        assertThat(properties.getLock().getRetryInterval()).isEqualTo(Duration.ofMillis(50));
        assertThat(properties.getLock().getRetryTimes()).isEqualTo(30);

        // 默认开启 TCC 防悬挂与幂等屏障
        assertThat(properties.getTcc().isFenceEnabled()).isTrue();
        assertThat(properties.getTcc().getCleanPeriod()).isEqualTo(Duration.ofDays(1));
        assertThat(properties.getTcc().getLogTtl()).isEqualTo(Duration.ofDays(3));
        assertThat(properties.getTcc().getTableName()).isEqualTo("tcc_fence_log");
    }

    @Test
    @DisplayName("测试外部环境属性绑定覆盖默认值")
    void testPropertyBinding() {
        contextRunner
                .withPropertyValues(
                        "ateng.cloud.seata.enabled=true",
                        "ateng.cloud.seata.tx.default-timeout=45s",
                        "ateng.cloud.seata.lock.retry-interval=80ms",
                        "ateng.cloud.seata.lock.retry-times=50",
                        "ateng.cloud.seata.tcc.fence-enabled=false",
                        "ateng.cloud.seata.tcc.clean-period=2d",
                        "ateng.cloud.seata.tcc.log-ttl=7d",
                        "ateng.cloud.seata.tcc.table-name=custom_tcc_fence"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(SeataProperties.class);
                    SeataProperties properties = context.getBean(SeataProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getTx().getDefaultTimeout()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(properties.getLock().getRetryInterval()).isEqualTo(Duration.ofMillis(80));
                    assertThat(properties.getLock().getRetryTimes()).isEqualTo(50);
                    assertThat(properties.getTcc().isFenceEnabled()).isFalse();
                    assertThat(properties.getTcc().getCleanPeriod()).isEqualTo(Duration.ofDays(2));
                    assertThat(properties.getTcc().getLogTtl()).isEqualTo(Duration.ofDays(7));
                    assertThat(properties.getTcc().getTableName()).isEqualTo("custom_tcc_fence");
                });
    }
}
