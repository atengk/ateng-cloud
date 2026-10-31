package io.github.atengk.cloud.seata.autoconfigure;

import io.github.atengk.cloud.seata.aspect.SeataExceptionTranslationAspect;
import io.github.atengk.cloud.seata.aspect.SeataGlobalLockAspect;
import io.github.atengk.cloud.seata.autoconfigure.AtengSeataAutoConfiguration.SeataMarker;
import io.github.atengk.cloud.seata.context.SeataAsyncContextSnapshotProvider;
import io.github.atengk.cloud.seata.datasource.SeataDynamicDataSourceCoordinator;
import io.github.atengk.cloud.seata.datasource.SeataDynamicDataSourcePropertiesCustomizer;
import io.github.atengk.cloud.seata.datasource.guard.SeataDynamicDatasourceGuard;
import io.github.atengk.cloud.seata.interceptor.SeataFeignContextInterceptor;
import io.github.atengk.cloud.seata.interceptor.SeataWebHandlerInterceptor;
import io.github.atengk.cloud.seata.lock.SeataGlobalLockTemplate;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import io.github.atengk.cloud.seata.tcc.InMemoryTccFenceRepository;
import io.github.atengk.cloud.seata.tcc.JdbcTccFenceRepository;
import io.github.atengk.cloud.seata.tcc.SeataTccFenceTemplate;
import io.github.atengk.cloud.seata.tcc.TccFenceBarrier;
import io.github.atengk.cloud.seata.tcc.TccFenceRepository;
import io.github.atengk.cloud.seata.tcc.TccFenceTableInitializer;
import io.github.atengk.cloud.seata.tenant.SeataTenantLineHandlerPostProcessor;
import org.apache.seata.rm.GlobalLockTemplate;
import org.apache.seata.rm.fence.SpringFenceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分布式事务增强自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengSeataAutoConfigurationTest {

    private final ApplicationContextRunner nonWebRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSeataAutoConfiguration.class));

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSeataAutoConfiguration.class));

    @Test
    @DisplayName("测试非 Web 环境下自动装配核心组件与默认属性")
    void testNonWebContext() {
        nonWebRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengSeataAutoConfiguration.class);
            assertThat(context).hasSingleBean(AtengSeataAutoConfiguration.SeataMarker.class);
            assertThat(context).hasSingleBean(SeataProperties.class);
            assertThat(context).hasSingleBean(SeataFeignContextInterceptor.class);
            assertThat(context).hasSingleBean(SeataExceptionTranslationAspect.class);
            assertThat(context).hasSingleBean(SeataAsyncContextSnapshotProvider.class);
            assertThat(context).hasSingleBean(SeataTenantLineHandlerPostProcessor.class);
            assertThat(context).hasSingleBean(SeataDynamicDataSourcePropertiesCustomizer.class);
            assertThat(context).hasSingleBean(SeataDynamicDataSourceCoordinator.class);
            assertThat(context).hasSingleBean(SeataDynamicDatasourceGuard.class);
            assertThat(context).hasSingleBean(SeataGlobalLockAspect.class);
            assertThat(context).hasSingleBean(SeataGlobalLockTemplate.class);
            assertThat(context).hasSingleBean(GlobalLockTemplate.class);
            assertThat(context).hasSingleBean(TccFenceRepository.class);
            assertThat(context).hasSingleBean(TccFenceBarrier.class);
            assertThat(context).hasSingleBean(SeataTccFenceTemplate.class);
            assertThat(context).doesNotHaveBean(SeataWebHandlerInterceptor.class);
            assertThat(context).doesNotHaveBean(TccFenceTableInitializer.class);

            SeataProperties properties = context.getBean(SeataProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getTx().getDefaultTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getLock().getRetryInterval()).isEqualTo(Duration.ofMillis(50));
            assertThat(properties.getLock().getRetryTimes()).isEqualTo(30);
            assertThat(properties.getTcc().isFenceEnabled()).isTrue();
            assertThat(properties.getTcc().isAutoInitTable()).isFalse();
            assertThat(properties.getDynamicDatasource().isEnabled()).isTrue();
            assertThat(properties.getDynamicDatasource().isGuardEnabled()).isTrue();
            assertThat(properties.getDynamicDatasource().isAutoProxy()).isTrue();
        });
    }

    @Test
    @DisplayName("测试 Servlet Web 环境下自动装配 Web 入站拦截器与配置")
    void testServletWebContext() {
        servletRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengSeataAutoConfiguration.class);
            assertThat(context).hasSingleBean(SeataMarker.class);
            assertThat(context).hasSingleBean(SeataProperties.class);
            assertThat(context).hasSingleBean(SeataFeignContextInterceptor.class);
            assertThat(context).hasSingleBean(SeataWebHandlerInterceptor.class);
            assertThat(context).hasSingleBean(SeataWebMvcConfigurer.class);
            assertThat(context).hasSingleBean(SeataExceptionTranslationAspect.class);
            assertThat(context).hasSingleBean(SeataAsyncContextSnapshotProvider.class);
            assertThat(context).hasSingleBean(SeataTenantLineHandlerPostProcessor.class);
            assertThat(context).hasSingleBean(SeataDynamicDataSourcePropertiesCustomizer.class);
            assertThat(context).hasSingleBean(SeataDynamicDataSourceCoordinator.class);
            assertThat(context).hasSingleBean(SeataDynamicDatasourceGuard.class);
            assertThat(context).hasSingleBean(SeataGlobalLockAspect.class);
            assertThat(context).hasSingleBean(SeataGlobalLockTemplate.class);
            assertThat(context).hasSingleBean(GlobalLockTemplate.class);
            assertThat(context).hasSingleBean(TccFenceRepository.class);
            assertThat(context).hasSingleBean(TccFenceBarrier.class);
            assertThat(context).hasSingleBean(SeataTccFenceTemplate.class);
        });
    }

    @Test
    @DisplayName("测试配置 ateng.cloud.seata.dynamic-datasource.enabled=false 时动态数据源定制与协调组件条件跳过")
    void testDisabledDynamicDatasource() {
        nonWebRunner
                .withPropertyValues("ateng.cloud.seata.dynamic-datasource.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengSeataAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(SeataDynamicDataSourcePropertiesCustomizer.class);
                    assertThat(context).doesNotHaveBean(SeataDynamicDataSourceCoordinator.class);
                    assertThat(context).hasSingleBean(SeataDynamicDatasourceGuard.class);
                });
    }

    @Test
    @DisplayName("测试配置 ateng.cloud.seata.dynamic-datasource.guard-enabled=false 时切源守卫条件跳过")
    void testDisabledDatasourceGuard() {
        nonWebRunner
                .withPropertyValues("ateng.cloud.seata.dynamic-datasource.guard-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengSeataAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(SeataDynamicDatasourceGuard.class);
                    assertThat(context).hasSingleBean(SeataDynamicDataSourcePropertiesCustomizer.class);
                });
    }

    @Test
    @DisplayName("测试配置 ateng.cloud.seata.enabled=false 时装配类条件跳过")
    void testDisabledConfiguration() {
        servletRunner
                .withPropertyValues("ateng.cloud.seata.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengSeataAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(SeataMarker.class);
                    assertThat(context).doesNotHaveBean(SeataFeignContextInterceptor.class);
                    assertThat(context).doesNotHaveBean(SeataWebHandlerInterceptor.class);
                    assertThat(context).doesNotHaveBean(SeataAsyncContextSnapshotProvider.class);
                    assertThat(context).doesNotHaveBean(SeataTenantLineHandlerPostProcessor.class);
                });
    }

    @Test
    @DisplayName("测试自定义 SeataMarker 时遵循 @ConditionalOnMissingBean 守卫")
    void testCustomMarkerOverride() {
        SeataProperties customProps = new SeataProperties();
        SeataMarker customMarker = new SeataMarker(customProps);

        nonWebRunner
                .withBean("customSeataMarker", SeataMarker.class, () -> customMarker)
                .run(context -> {
                    assertThat(context).hasSingleBean(SeataMarker.class);
                    assertThat(context.getBean(SeataMarker.class)).isSameAs(customMarker);
                });
    }

    @Test
    @DisplayName("测试配置 ateng.cloud.seata.tcc.fence-enabled=false 时 TCC Fence 组件条件跳过")
    void testDisabledTccFence() {
        nonWebRunner
                .withPropertyValues("ateng.cloud.seata.tcc.fence-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengSeataAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(TccFenceRepository.class);
                    assertThat(context).doesNotHaveBean(TccFenceBarrier.class);
                    assertThat(context).doesNotHaveBean(SeataTccFenceTemplate.class);
                });
    }

    @Test
    @DisplayName("测试注册 DataSource 与 PlatformTransactionManager 时装配 JdbcTccFenceRepository 与 SpringFenceConfig")
    void testTccFenceWithDataSource() {
        javax.sql.DataSource mockDs = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        org.springframework.transaction.PlatformTransactionManager mockTxManager = org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);

        nonWebRunner
                .withBean(javax.sql.DataSource.class, () -> mockDs)
                .withBean(org.springframework.transaction.PlatformTransactionManager.class, () -> mockTxManager)
                .run(context -> {
                    assertThat(context).hasSingleBean(TccFenceRepository.class);
                    assertThat(context.getBean(TccFenceRepository.class)).isInstanceOf(JdbcTccFenceRepository.class);
                    assertThat(context).hasSingleBean(TccFenceBarrier.class);
                    assertThat(context).hasSingleBean(SeataTccFenceTemplate.class);
                    assertThat(context).hasSingleBean(SpringFenceConfig.class);
                });
    }

    @Test
    @DisplayName("测试配置自定义 TCC 表名时自动注入 JdbcTccFenceRepository 与多租户后置处理器")
    void testCustomTableNameInjection() {
        javax.sql.DataSource mockDs = org.mockito.Mockito.mock(javax.sql.DataSource.class);

        nonWebRunner
                .withPropertyValues("ateng.cloud.seata.tcc.table-name=my_custom_fence_tbl")
                .withBean(javax.sql.DataSource.class, () -> mockDs)
                .run(context -> {
                    assertThat(context).hasSingleBean(TccFenceRepository.class);
                    JdbcTccFenceRepository repo = (JdbcTccFenceRepository) context.getBean(TccFenceRepository.class);
                    assertThat(repo.getTableName()).isEqualTo("my_custom_fence_tbl");

                    assertThat(context).hasSingleBean(SeataTenantLineHandlerPostProcessor.class);
                    SeataTenantLineHandlerPostProcessor processor = context.getBean(SeataTenantLineHandlerPostProcessor.class);
                    assertThat(processor.getCustomTccTableName()).isEqualTo("my_custom_fence_tbl");
                });
    }

    @Test
    @DisplayName("测试 auto-init-table 条件装配：仅当配置为 true 且存在 DataSource 时才装配初始化器")
    void testAutoInitTableConditionalRegistration() {
        org.h2.jdbcx.JdbcDataSource realDs = new org.h2.jdbcx.JdbcDataSource();
        realDs.setURL("jdbc:h2:mem:auto_init_cfg_test;DB_CLOSE_DELAY=-1");
        realDs.setUser("sa");
        realDs.setPassword("");

        // 默认情况下（未配置或为 false）不装配
        nonWebRunner
                .withBean(javax.sql.DataSource.class, () -> realDs)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TccFenceTableInitializer.class);
                });

        // 显式开启 auto-init-table=true 时成功装配
        nonWebRunner
                .withPropertyValues("ateng.cloud.seata.tcc.auto-init-table=true")
                .withBean(javax.sql.DataSource.class, () -> realDs)
                .run(context -> {
                    assertThat(context).hasSingleBean(TccFenceTableInitializer.class);
                });
    }
}
