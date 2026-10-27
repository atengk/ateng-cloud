package io.github.atengk.cloud.nacos.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NacosConfigHealthChecker} 单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class NacosConfigHealthCheckerTest {

    @Test
    @DisplayName("导入指令探测：支持单一属性与列表索引语法声明")
    void testDetectConfigImportDirective() {
        AtengNacosProperties properties = new AtengNacosProperties();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();

        // 1. 无导入指令
        MockEnvironment envNone = new MockEnvironment();
        NacosConfigHealthChecker checkerNone = new NacosConfigHealthChecker(
                properties, envNone, beanFactory.getBeanProvider(NacosConfigManager.class));
        assertThat(checkerNone.detectConfigImportDirective()).isFalse();

        // 2. 单一属性声明 spring.config.import
        MockEnvironment envSingle = new MockEnvironment();
        envSingle.setProperty("spring.config.import", "optional:nacos:app.yml?refreshEnabled=true");
        NacosConfigHealthChecker checkerSingle = new NacosConfigHealthChecker(
                properties, envSingle, beanFactory.getBeanProvider(NacosConfigManager.class));
        assertThat(checkerSingle.detectConfigImportDirective()).isTrue();

        // 3. 列表语法声明 spring.config.import[0]
        MockEnvironment envList = new MockEnvironment();
        envList.setProperty("spring.config.import[0]", "optional:nacos:baseline.yml");
        envList.setProperty("spring.config.import[1]", "optional:nacos:order-service.yml");
        NacosConfigHealthChecker checkerList = new NacosConfigHealthChecker(
                properties, envList, beanFactory.getBeanProvider(NacosConfigManager.class));
        assertThat(checkerList.detectConfigImportDirective()).isTrue();
    }

    @Test
    @DisplayName("激活配置源探测：准确识别包含 nacos 标识的 PropertySource")
    void testDetectActiveNacosPropertySources() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("nacos:common-baseline.yml", Map.of()));
        environment.getPropertySources().addLast(new MapPropertySource("applicationConfig: [classpath:/application.yml]", Map.of()));
        environment.getPropertySources().addLast(new MapPropertySource("NacosPropertySource-order-service.yml", Map.of()));

        AtengNacosProperties properties = new AtengNacosProperties();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        NacosConfigHealthChecker checker = new NacosConfigHealthChecker(
                properties, environment, beanFactory.getBeanProvider(NacosConfigManager.class));

        List<String> sources = checker.detectActiveNacosPropertySources();
        assertThat(sources).containsExactly("nacos:common-baseline.yml", "NacosPropertySource-order-service.yml");
    }

    @Test
    @DisplayName("连接状态探测：在未装配、正常与异常时平滑输出对应状态")
    void testDetectServerStatus() throws Exception {
        AtengNacosProperties properties = new AtengNacosProperties();

        // 1. 未装配 ConfigManager
        StaticListableBeanFactory emptyFactory = new StaticListableBeanFactory();
        NacosConfigHealthChecker checkerUnavail = new NacosConfigHealthChecker(
                properties, new MockEnvironment(), emptyFactory.getBeanProvider(NacosConfigManager.class));
        assertThat(checkerUnavail.detectServerStatus()).isEqualTo("UNAVAILABLE");

        // 2. 正常连接就绪
        NacosConfigManager normalManager = mock(NacosConfigManager.class);
        ConfigService normalService = mock(ConfigService.class);
        when(normalManager.getConfigService()).thenReturn(normalService);
        when(normalService.getServerStatus()).thenReturn("UP");

        StaticListableBeanFactory normalFactory = new StaticListableBeanFactory();
        normalFactory.addBean("configManager", normalManager);
        NacosConfigHealthChecker checkerNormal = new NacosConfigHealthChecker(
                properties, new MockEnvironment(), normalFactory.getBeanProvider(NacosConfigManager.class));
        assertThat(checkerNormal.detectServerStatus()).isEqualTo("UP");

        // 3. 抛出异常降级为 DISCONNECTED
        NacosConfigManager brokenManager = mock(NacosConfigManager.class);
        ConfigService brokenService = mock(ConfigService.class);
        when(brokenManager.getConfigService()).thenReturn(brokenService);
        when(brokenService.getServerStatus()).thenThrow(new RuntimeException("Socket closed"));

        StaticListableBeanFactory brokenFactory = new StaticListableBeanFactory();
        brokenFactory.addBean("configManager", brokenManager);
        NacosConfigHealthChecker checkerBroken = new NacosConfigHealthChecker(
                properties, new MockEnvironment(), brokenFactory.getBeanProvider(NacosConfigManager.class));
        assertThat(checkerBroken.detectServerStatus()).isEqualTo("DISCONNECTED");
    }

    @Test
    @DisplayName("启动自检门禁：配置关闭时静默跳过，配置开启时安全自检")
    void testAfterSingletonsInstantiated() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getConfig().setCheckOnStartup(false);

        MockEnvironment environment = new MockEnvironment();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();

        NacosConfigHealthChecker checker = new NacosConfigHealthChecker(
                properties, environment, beanFactory.getBeanProvider(NacosConfigManager.class));

        // 当 checkOnStartup 为 false 时直接跳过
        checker.afterSingletonsInstantiated();

        // 开启后分别覆盖有导入指令/无导入指令分支
        properties.getConfig().setCheckOnStartup(true);
        checker.afterSingletonsInstantiated();

        environment.setProperty("spring.config.import", "optional:nacos:test.yml");
        checker.afterSingletonsInstantiated();
    }
}
