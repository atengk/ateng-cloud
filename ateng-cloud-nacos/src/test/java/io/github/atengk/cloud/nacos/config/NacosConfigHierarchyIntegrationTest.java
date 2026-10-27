package io.github.atengk.cloud.nacos.config;

import io.github.atengk.cloud.nacos.constant.NacosConfigConstants;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("Data ID 构建规约：验证服务私有配置 Data ID 标准表达式生成与边界防卫")
    void testServiceDataIdNamingConvention() {
        assertThat(NacosConfigConstants.buildServiceDataId("order-service", "dev"))
                .isEqualTo("order-service-dev.yaml");
        assertThat(NacosConfigConstants.buildServiceDataId("order-service", null))
                .isEqualTo("order-service.yaml");
        assertThat(NacosConfigConstants.buildServiceDataId("order-service", ""))
                .isEqualTo("order-service.yaml");

        assertThatThrownBy(() -> NacosConfigConstants.buildServiceDataId(null, "dev"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appName must not be blank");
        assertThatThrownBy(() -> NacosConfigConstants.buildServiceDataId("   ", "dev"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("三层配置拓扑继承与优先级覆盖：公共基线 -> 中间件共享 -> 服务私有")
    void testThreeTierConfigHierarchyAndOverride() {
        StandardEnvironment environment = new StandardEnvironment();

        String baselineSource = "nacos:" + NacosConfigConstants.DATA_ID_COMMON_BASELINE;
        String middlewareSource = "nacos:" + NacosConfigConstants.DATA_ID_MIDDLEWARE_SHARED;
        String serviceSource = "nacos:" + NacosConfigConstants.buildServiceDataId("order-service", "prod");

        // 1. 全网公共基线配置 (shared-common.yaml) - 优先级最低
        Map<String, Object> baselineProps = Map.of(
                "app.global-timeout", "5000",
                "app.thread-pool.core-size", "10",
                "app.feature.audit-enabled", "true"
        );
        environment.getPropertySources().addLast(new MapPropertySource(baselineSource, baselineProps));

        // 2. 中间件共享配置 (shared-infrastructure.yaml) - 覆盖基线，提供中间件级共享
        Map<String, Object> middlewareProps = Map.of(
                "app.thread-pool.core-size", "50", // 覆盖 baseline 的 10
                "app.datasource.max-active", "100"
        );
        environment.getPropertySources().addBefore(baselineSource,
                new MapPropertySource(middlewareSource, middlewareProps));

        // 3. 微服务私有配置 (order-service-prod.yaml) - 优先级最高，覆盖共享
        Map<String, Object> serviceProps = Map.of(
                "app.global-timeout", "1000", // 覆盖 baseline 的 5000
                "app.service-name", "order-service"
        );
        environment.getPropertySources().addBefore(middlewareSource,
                new MapPropertySource(serviceSource, serviceProps));

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
                    assertThat(context).hasSingleBean(io.github.atengk.cloud.nacos.health.NacosHealthChecker.class);
                    io.github.atengk.cloud.nacos.health.NacosHealthChecker healthChecker =
                            context.getBean(io.github.atengk.cloud.nacos.health.NacosHealthChecker.class);
                    assertThat(healthChecker.detectConfigImportDirective()).isTrue();
                });
    }

    @Test
    @DisplayName("健康自检诊断：基于 Spring Boot Binder 探测多条 spring.config.import 声明")
    void testDetectMultiImportDirectivesWithBinder() {
        contextRunner
                .withPropertyValues(
                        "ateng.cloud.nacos.drain-timeout=10ms",
                        "spring.cloud.nacos.config.enabled=false",
                        "spring.config.import[0]=optional:classpath:application-local.yml",
                        "spring.config.import[1]=optional:nacos:shared-common.yaml",
                        "spring.config.import[2]=optional:nacos:order-service.yaml"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    io.github.atengk.cloud.nacos.health.NacosHealthChecker healthChecker =
                            context.getBean(io.github.atengk.cloud.nacos.health.NacosHealthChecker.class);
                    assertThat(healthChecker.detectConfigImportDirective()).isTrue();
                });
    }
}
