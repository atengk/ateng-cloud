package io.github.atengk.cloud.seata.autoconfigure;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import feign.RequestInterceptor;
import io.github.atengk.cloud.seata.aspect.SeataExceptionTranslationAspect;
import io.github.atengk.cloud.seata.constant.SeataConstants;
import io.github.atengk.cloud.seata.context.SeataAsyncContextSnapshotProvider;
import io.github.atengk.cloud.seata.interceptor.SeataFeignContextInterceptor;
import io.github.atengk.cloud.seata.interceptor.SeataWebHandlerInterceptor;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import io.github.atengk.cloud.seata.tenant.SeataTenantLineHandlerPostProcessor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
     * 注册 Feign 客户端 XID 透传拦截器（当引入 OpenFeign 时生效）
     *
     * @return SeataFeignContextInterceptor 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(RequestInterceptor.class)
    public SeataFeignContextInterceptor seataFeignContextInterceptor() {
        log.debug("装配 SeataFeignContextInterceptor 跨进程 XID 透传拦截器");
        return new SeataFeignContextInterceptor();
    }

    /**
     * 注册 WebMVC 入站 XID 上下文生命周期拦截器
     *
     * @return SeataWebHandlerInterceptor 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(HandlerInterceptor.class)
    public SeataWebHandlerInterceptor seataWebHandlerInterceptor() {
        log.debug("装配 SeataWebHandlerInterceptor Web 入站 XID 生命周期拦截器");
        return new SeataWebHandlerInterceptor();
    }

    /**
     * 注册 WebMvcConfigurer 将 XID 拦截器挂载至请求过滤链
     *
     * @param seataWebHandlerInterceptor 入站拦截器
     * @return SeataWebMvcConfigurer 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(WebMvcConfigurer.class)
    public SeataWebMvcConfigurer seataWebMvcConfigurer(SeataWebHandlerInterceptor seataWebHandlerInterceptor) {
        log.debug("装配 SeataWebMvcConfigurer WebMVC 拦截器注册配置");
        return new SeataWebMvcConfigurer(seataWebHandlerInterceptor);
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
     * 注册 MyBatis-Plus 多租户内部表自动排除后置处理器（当存在 TenantLineHandler 时生效）
     *
     * @return SeataTenantLineHandlerPostProcessor 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(TenantLineHandler.class)
    public static SeataTenantLineHandlerPostProcessor seataTenantLineHandlerPostProcessor() {
        log.debug("装配 SeataTenantLineHandlerPostProcessor 多租户内部表排除后置处理器");
        return new SeataTenantLineHandlerPostProcessor();
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
