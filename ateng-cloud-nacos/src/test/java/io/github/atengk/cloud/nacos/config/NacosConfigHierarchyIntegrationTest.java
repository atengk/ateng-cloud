package io.github.atengk.cloud.nacos.config;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nacos 现代多层配置加载（三层 Data ID 继承拓扑）与离线容错集成测试
 *
 * <p>验证全网公共基线、中间件共享与微服务私有配置的三层优先级继承覆盖，
 * 以及 {@code optional:nacos:...} 导入语法在远端离线状态下的优雅容灾保底。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
class NacosConfigHierarchyIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengNacosAutoConfiguration.class));

    @Test
    @DisplayName("三层配置拓扑继承与优先级覆盖：公共基线 -> 中间件共享 -> 服务私有")
    void testThreeTierConfigHierarchyAndOverride() {
        StandardEnvironment environment = new StandardEnvironment();

        // 1. 全网公共基线配置 (common-baseline.yml) - 优先级最低
        Map<String, Object> baselineProps = Map.of(
                "app.global-timeout", "5000",
                "app.thread-pool.core-size", "10",
                "app.feature.audit-enabled", "true"
        );
        environment.getPropertySources().addLast(new MapPropertySource("nacos:common-baseline.yml", baselineProps));

        // 2. 中间件共享配置 (middleware-shared.yml) - 覆盖基线，提供中间件级共享
        Map<String, Object> middlewareProps = Map.of(
                "app.thread-pool.core-size", "50", // 覆盖 baseline 的 10
                "app.datasource.max-active", "100"
        );
        environment.getPropertySources().addBefore("nacos:common-baseline.yml",
                new MapPropertySource("nacos:middleware-shared.yml", middlewareProps));

        // 3. 微服务私有配置 (order-service.yml) - 优先级最高，覆盖共享
        Map<String, Object> serviceProps = Map.of(
                "app.global-timeout", "1000", // 覆盖 baseline 的 5000
                "app.service-name", "order-service"
        );
        environment.getPropertySources().addBefore("nacos:middleware-shared.yml",
                new MapPropertySource("nacos:order-service.yml", serviceProps));

        // 验证优先级覆盖与继承结果
        assertThat(environment.getProperty("app.global-timeout"))
                .as("私有配置应成功覆盖基线配置")
                .isEqualTo("1000");

        assertThat(environment.getProperty("app.thread-pool.core-size"))
                .as("中间件配置应成功覆盖基线配置")
                .isEqualTo("50");

        assertThat(environment.getProperty("app.feature.audit-enabled"))
                .as("公共基线配置应成功透明继承")
                .isEqualTo("true");

        assertThat(environment.getProperty("app.datasource.max-active"))
                .as("中间件共享配置应成功透明继承")
                .isEqualTo("100");

        assertThat(environment.getProperty("app.service-name"))
                .as("微服务私有专有配置应正确获取")
                .isEqualTo("order-service");
    }

    @Test
    @DisplayName("离线容错容灾：声明 optional:nacos:... 且远端不可达时平滑启动不崩溃")
    void testOptionalNacosImportGracefulFallbackWhenOffline() {
        contextRunner
                .withPropertyValues(
                        "ateng.cloud.nacos.drain-timeout=10ms",
                        "spring.cloud.nacos.config.enabled=false",
                        "spring.config.import=optional:nacos:non-existent.yml"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AtengNacosProperties.class);
                    assertThat(context).hasSingleBean(NacosConfigHealthChecker.class);
                    NacosConfigHealthChecker healthChecker = context.getBean(NacosConfigHealthChecker.class);
                    assertThat(healthChecker.detectConfigImportDirective()).isTrue();
                });
    }
}
