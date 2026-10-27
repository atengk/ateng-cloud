package io.github.atengk.cloud.nacos.health;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import io.github.atengk.cloud.nacos.metadata.DefaultNacosMetadataProvider;
import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NacosHealthChecker} 全景健康就绪诊断器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
class NacosHealthCheckerTest {

    @Test
    @DisplayName("K8s 停机安全预警：drain-timeout 超出 25s 阈值时触发安全告警")
    void testDrainTimeoutWarningWhenExceedingThreshold() {
        AtengNacosProperties properties = new AtengNacosProperties();
        MockEnvironment env = new MockEnvironment();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();

        // 1. 安全配置：10s（<= 25s）
        properties.setDrainTimeout(Duration.ofSeconds(10));
        NacosHealthChecker safeChecker = new NacosHealthChecker(
                properties, env, beanFactory.getBeanProvider(NacosConfigManager.class)
        );
        assertThat(safeChecker.checkDrainTimeout()).isTrue();

        // 2. 刚好处于阈值：25s
        properties.setDrainTimeout(Duration.ofSeconds(25));
        assertThat(safeChecker.checkDrainTimeout()).isTrue();

        // 3. 超标配置：30s（> 25s，防范 K8s SIGKILL 强杀）
        properties.setDrainTimeout(Duration.ofSeconds(30));
        assertThat(safeChecker.checkDrainTimeout()).isFalse();
    }

    @Test
    @DisplayName("本地容灾快照磁盘可写性预检：可写目录返回 true，只读或非法路径返回 false 并触发告警")
    void testSnapshotDirectoryWritabilityProbe(@TempDir Path tempDir) {
        AtengNacosProperties properties = new AtengNacosProperties();
        MockEnvironment env = new MockEnvironment();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();

        NacosHealthChecker checker = new NacosHealthChecker(
                properties, env, beanFactory.getBeanProvider(NacosConfigManager.class)
        );

        // 1. 正常可写临时目录
        Path writableDir = tempDir.resolve("snapshot-writable");
        assertThat(checker.checkSnapshotDirectoryWritable(writableDir)).isTrue();

        // 2. 模拟不可写路径：将文件作为目录路径导致无法创建目录
        File existingFile = tempDir.resolve("conflict-file").toFile();
        try {
            boolean created = existingFile.createNewFile();
            assertThat(created).isTrue();
        } catch (Exception e) {
            // ignore
        }
        Path impossibleDir = existingFile.toPath().resolve("sub-dir");
        assertThat(checker.checkSnapshotDirectoryWritable(impossibleDir)).isFalse();

        // 3. 空路径防御
        assertThat(checker.checkSnapshotDirectoryWritable(null)).isFalse();
    }

    @Test
    @DisplayName("导入指令探测：支持单一属性与列表索引语法声明")
    void testDetectConfigImportDirective() {
        AtengNacosProperties properties = new AtengNacosProperties();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();

        // 1. 无导入指令
        MockEnvironment envNone = new MockEnvironment();
        NacosHealthChecker checkerNone = new NacosHealthChecker(
                properties, envNone, beanFactory.getBeanProvider(NacosConfigManager.class));
        assertThat(checkerNone.detectConfigImportDirective()).isFalse();

        // 2. 单一属性声明 spring.config.import
        MockEnvironment envSingle = new MockEnvironment();
        envSingle.setProperty("spring.config.import", "optional:nacos:app.yml?refreshEnabled=true");
        NacosHealthChecker checkerSingle = new NacosHealthChecker(
                properties, envSingle, beanFactory.getBeanProvider(NacosConfigManager.class));
        assertThat(checkerSingle.detectConfigImportDirective()).isTrue();

        // 3. 列表语法声明 spring.config.import[0]
        MockEnvironment envList = new MockEnvironment();
        envList.setProperty("spring.config.import[0]", "optional:nacos:baseline.yml");
        envList.setProperty("spring.config.import[1]", "optional:nacos:order-service.yml");
        NacosHealthChecker checkerList = new NacosHealthChecker(
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
        NacosHealthChecker checker = new NacosHealthChecker(
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
        NacosHealthChecker checkerUnavail = new NacosHealthChecker(
                properties, new MockEnvironment(), emptyFactory.getBeanProvider(NacosConfigManager.class));
        assertThat(checkerUnavail.detectServerStatus()).isEqualTo("UNAVAILABLE");

        // 2. 正常连接就绪
        NacosConfigManager normalManager = mock(NacosConfigManager.class);
        ConfigService normalService = mock(ConfigService.class);
        when(normalManager.getConfigService()).thenReturn(normalService);
        when(normalService.getServerStatus()).thenReturn("UP");

        StaticListableBeanFactory normalFactory = new StaticListableBeanFactory();
        normalFactory.addBean("configManager", normalManager);
        NacosHealthChecker checkerNormal = new NacosHealthChecker(
                properties, new MockEnvironment(), normalFactory.getBeanProvider(NacosConfigManager.class));
        assertThat(checkerNormal.detectServerStatus()).isEqualTo("UP");

        // 3. 抛出异常降级为 DISCONNECTED
        NacosConfigManager brokenManager = mock(NacosConfigManager.class);
        ConfigService brokenService = mock(ConfigService.class);
        when(brokenManager.getConfigService()).thenReturn(brokenService);
        when(brokenService.getServerStatus()).thenThrow(new RuntimeException("Socket closed"));

        StaticListableBeanFactory brokenFactory = new StaticListableBeanFactory();
        brokenFactory.addBean("configManager", brokenManager);
        NacosHealthChecker checkerBroken = new NacosHealthChecker(
                properties, new MockEnvironment(), brokenFactory.getBeanProvider(NacosConfigManager.class));
        assertThat(checkerBroken.detectServerStatus()).isEqualTo("DISCONNECTED");
    }

    @Test
    @DisplayName("启动全景自检报告：多维元数据、机房亲和与预热全量汇总输出")
    void testPanoramaHealthReportOutput() {
        AtengNacosProperties properties = new AtengNacosProperties();
        properties.setZone("shanghai-az2");
        properties.setCluster("prod-cluster");
        properties.setDrainTimeout(Duration.ofSeconds(15));
        properties.getMetadata().setWarmupDuration(Duration.ofSeconds(60));

        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "trade-service");
        environment.setProperty("spring.config.import", "optional:nacos:trade-service.yaml");

        NacosMetadataProvider metadataProvider = mock(NacosMetadataProvider.class);
        when(metadataProvider.getMetadata()).thenReturn(Map.of(
                DefaultNacosMetadataProvider.KEY_STARTUP_TIME, String.valueOf(System.currentTimeMillis() - 10_000L),
                DefaultNacosMetadataProvider.KEY_WARMUP_DURATION, "60s",
                DefaultNacosMetadataProvider.KEY_ZONE, "shanghai-az2",
                DefaultNacosMetadataProvider.KEY_VERSION, "2.0.0"
        ));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("metadataProvider", metadataProvider);

        NacosHealthChecker checker = new NacosHealthChecker(
                properties, environment,
                beanFactory.getBeanProvider(NacosConfigManager.class),
                beanFactory.getBeanProvider(NacosMetadataProvider.class),
                null
        );

        // 执行单例初始化后自检
        checker.afterSingletonsInstantiated();
        checker.checkAndLogStatus();
    }
}
