package io.github.atengk.cloud.seata.autoconfigure;

import io.github.atengk.cloud.seata.aspect.SeataExceptionTranslationAspect;
import io.github.atengk.cloud.seata.aspect.SeataGlobalLockAspect;
import io.github.atengk.cloud.seata.constant.SeataConstants;
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
import io.github.atengk.cloud.seata.tenant.SeataTenantLineHandlerPostProcessor;
import org.apache.seata.common.holder.ObjectHolder;
import org.apache.seata.rm.GlobalLockTemplate;
import org.apache.seata.rm.fence.SpringFenceConfig;
import org.apache.seata.spring.annotation.GlobalLock;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 分布式事务增强底座自动装配类
 *
 * @author Ateng
 * @since 2026-10-29
 */
@AutoConfiguration
@EnableConfigurationProperties(SeataProperties.class)
@ConditionalOnProperty(prefix = SeataConstants.PROPERTIES_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengSeataAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengSeataAutoConfiguration.class);

    public AtengSeataAutoConfiguration() {
        log.info("初始化 ateng-cloud-seata 分布式事务增强自动装配底座");
    }

    /**
     * 注册分布式事务增强标记 Bean（当配置生效时对外供给）
     *
     * @param properties Seata 配置属性
     * @return SeataMarker 标记实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SeataMarker seataMarker(SeataProperties properties) {
        log.debug("装配 SeataMarker 事务标记组件，全局事务默认超时: {}s，锁重试间隔: {}ms",
                properties.getTx().getDefaultTimeout().toSeconds(),
                properties.getLock().getRetryInterval().toMillis());
        return new SeataMarker(properties);
    }

    /**
     * Feign 跨进程 XID 透传拦截器装配配置
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    public static class SeataFeignConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SeataFeignContextInterceptor seataFeignContextInterceptor() {
            log.debug("装配 SeataFeignContextInterceptor 跨进程 XID 透传拦截器");
            return new SeataFeignContextInterceptor();
        }
    }

    /**
     * WebMVC 入站 XID 上下文生命周期拦截器装配配置
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.config.annotation.WebMvcConfigurer")
    public static class SeataWebMvcConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SeataWebHandlerInterceptor seataWebHandlerInterceptor() {
            log.debug("装配 SeataWebHandlerInterceptor Web 入站 XID 生命周期拦截器");
            return new SeataWebHandlerInterceptor();
        }

        @Bean
        @ConditionalOnMissingBean
        public SeataWebMvcConfigurer seataWebMvcConfigurer(SeataWebHandlerInterceptor seataWebHandlerInterceptor) {
            log.debug("装配 SeataWebMvcConfigurer WebMVC 拦截器注册配置");
            return new SeataWebMvcConfigurer(seataWebHandlerInterceptor);
        }
    }

    /**
     * 注册 Seata 异常因果转译切面
     *
     * @return SeataExceptionTranslationAspect 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ProceedingJoinPoint.class)
    public SeataExceptionTranslationAspect seataExceptionTranslationAspect() {
        log.debug("装配 SeataExceptionTranslationAspect 异常因果转译切面");
        return new SeataExceptionTranslationAspect();
    }

    /**
     * 注册 Seata 异步线程池快照提供者 Bean (同时已通过 SPI 注册至 AsyncContext)
     *
     * @return SeataAsyncContextSnapshotProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SeataAsyncContextSnapshotProvider seataAsyncContextSnapshotProvider() {
        log.debug("装配 SeataAsyncContextSnapshotProvider 异步线程池 XID 快照透传组件");
        return new SeataAsyncContextSnapshotProvider();
    }

    /**
     * MyBatis-Plus 多租户内部表自动排除配置
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler")
    public static class SeataTenantConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public static SeataTenantLineHandlerPostProcessor seataTenantLineHandlerPostProcessor(Environment env) {
            String customTccTableName = env != null
                    ? env.getProperty("ateng.cloud.seata.tcc.table-name", SeataConstants.DEFAULT_TCC_FENCE_TABLE_NAME)
                    : SeataConstants.DEFAULT_TCC_FENCE_TABLE_NAME;
            log.debug("装配 SeataTenantLineHandlerPostProcessor 多租户内部表排除后置处理器，自定义 TCC 表: {}", customTccTableName);
            return new SeataTenantLineHandlerPostProcessor(customTccTableName);
        }
    }

    /**
     * Baomidou dynamic-datasource 属性与连接池协同配置
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourcePropertiesCustomizer")
    public static class SeataDynamicDataSourceConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = SeataConstants.PROPERTIES_PREFIX + ".dynamic-datasource", name = "enabled", havingValue = "true", matchIfMissing = true)
        public SeataDynamicDataSourcePropertiesCustomizer seataDynamicDataSourcePropertiesCustomizer(SeataProperties properties) {
            log.debug("装配 SeataDynamicDataSourcePropertiesCustomizer 动态数据源属性定制器");
            return new SeataDynamicDataSourcePropertiesCustomizer(properties);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = SeataConstants.PROPERTIES_PREFIX + ".dynamic-datasource", name = "enabled", havingValue = "true", matchIfMissing = true)
        public SeataDynamicDataSourceCoordinator seataDynamicDataSourceCoordinator(SeataProperties properties) {
            log.debug("装配 SeataDynamicDataSourceCoordinator 动态数据源代理协同协调器");
            return new SeataDynamicDataSourceCoordinator(properties);
        }
    }

    /**
     * 注册协同 Seata 全局分布式事务的动态数据源切源安全守卫
     *
     * @param properties Seata 配置属性
     * @return SeataDynamicDatasourceGuard 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SeataConstants.PROPERTIES_PREFIX + ".dynamic-datasource", name = "guard-enabled", havingValue = "true", matchIfMissing = true)
    public SeataDynamicDatasourceGuard seataDynamicDatasourceGuard(SeataProperties properties) {
        log.debug("装配 SeataDynamicDatasourceGuard 动态数据源切源安全守卫");
        return new SeataDynamicDatasourceGuard(properties);
    }

    /**
     * 注册高并发轻量防脏读 @GlobalLock 声明式拦截切面
     *
     * @param properties Seata 配置属性
     * @return SeataGlobalLockAspect 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass({ProceedingJoinPoint.class, GlobalLock.class})
    public SeataGlobalLockAspect seataGlobalLockAspect(SeataProperties properties) {
        log.debug("装配 SeataGlobalLockAspect 全局排他锁声明式拦截切面");
        return new SeataGlobalLockAspect(properties);
    }

    /**
     * 注册编程式高并发轻量全局锁执行模板
     *
     * @param properties Seata 配置属性
     * @return SeataGlobalLockTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SeataGlobalLockTemplate seataGlobalLockTemplate(SeataProperties properties) {
        log.debug("装配 SeataGlobalLockTemplate 编程式全局锁执行模板");
        return new SeataGlobalLockTemplate(properties);
    }

    /**
     * 注册 Apache Seata 原生 GlobalLockTemplate 门面组件
     *
     * @return GlobalLockTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(GlobalLockTemplate.class)
    public GlobalLockTemplate globalLockTemplate() {
        log.debug("装配 Apache Seata 原生 GlobalLockTemplate 门面组件");
        return new GlobalLockTemplate();
    }

    /**
     * 注册基于 JDBC 数据源的 TCC Fence 本地日志仓储
     *
     * @param dataSource 数据源
     * @param properties 配置属性
     * @return JdbcTccFenceRepository 实例
     */
    @Bean
    @ConditionalOnMissingBean(TccFenceRepository.class)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = SeataConstants.PROPERTIES_PREFIX + ".tcc", name = "fence-enabled", havingValue = "true", matchIfMissing = true)
    public TccFenceRepository jdbcTccFenceRepository(DataSource dataSource, SeataProperties properties) {
        String tableName = (properties != null && properties.getTcc() != null)
                ? properties.getTcc().getTableName()
                : null;
        log.debug("装配 JdbcTccFenceRepository 本地数据库防卫仓储，表名: {}", tableName);
        return new JdbcTccFenceRepository(dataSource, tableName);
    }

    /**
     * 注册内存态自洽 TCC Fence 本地日志仓储（无物理 DataSource 时的保底装配）
     *
     * @return InMemoryTccFenceRepository 实例
     */
    @Bean
    @ConditionalOnMissingBean(TccFenceRepository.class)
    @ConditionalOnProperty(prefix = SeataConstants.PROPERTIES_PREFIX + ".tcc", name = "fence-enabled", havingValue = "true", matchIfMissing = true)
    public TccFenceRepository inMemoryTccFenceRepository() {
        log.debug("装配 InMemoryTccFenceRepository 纯内存防卫仓储");
        return new InMemoryTccFenceRepository();
    }

    /**
     * 注册受管 TCC Fence 业务防卫屏障组件
     *
     * @param repository TCC Fence 日志仓储
     * @return TccFenceBarrier 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SeataConstants.PROPERTIES_PREFIX + ".tcc", name = "fence-enabled", havingValue = "true", matchIfMissing = true)
    public TccFenceBarrier tccFenceBarrier(TccFenceRepository repository) {
        log.debug("装配 TccFenceBarrier 业务防卫屏障组件");
        return new TccFenceBarrier(repository);
    }

    /**
     * 注册编程式 TCC Fence 业务防卫执行模板
     *
     * @param barrier TCC Fence 业务防卫屏障
     * @return SeataTccFenceTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SeataConstants.PROPERTIES_PREFIX + ".tcc", name = "fence-enabled", havingValue = "true", matchIfMissing = true)
    public SeataTccFenceTemplate seataTccFenceTemplate(TccFenceBarrier barrier) {
        log.debug("装配 SeataTccFenceTemplate 编程式防卫执行模板");
        return new SeataTccFenceTemplate(barrier);
    }

    /**
     * 注册 Apache Seata 原生 SpringFenceConfig 配置（协同原生 @TwoPhaseBusinessAction 声明式防卫）
     *
     * @param dataSource         数据源
     * @param transactionManager 事务管理器
     * @return SpringFenceConfig 实例
     */
    @Bean
    @ConditionalOnMissingBean(SpringFenceConfig.class)
    @ConditionalOnClass(SpringFenceConfig.class)
    @ConditionalOnBean({DataSource.class, PlatformTransactionManager.class})
    @ConditionalOnProperty(prefix = SeataConstants.PROPERTIES_PREFIX + ".tcc", name = "fence-enabled", havingValue = "true", matchIfMissing = true)
    public SpringFenceConfig springFenceConfig(DataSource dataSource, PlatformTransactionManager transactionManager) {
        log.debug("装配 Apache Seata 原生 SpringFenceConfig 组件");
        SpringFenceConfig config = new SpringFenceConfig(dataSource, transactionManager);
        ObjectHolder.INSTANCE.setObject("SpringFenceConfig", config);
        return config;
    }

    /**
     * 分布式事务基础设施装配守卫标记类
     */
    public static class SeataMarker {
        private final SeataProperties properties;

        public SeataMarker(SeataProperties properties) {
            this.properties = properties;
        }

        public SeataProperties getProperties() {
            return properties;
        }
    }
}
