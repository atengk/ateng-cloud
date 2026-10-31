package io.github.atengk.cloud.sentinel.autoconfigure;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.RequestOriginParser;
import com.alibaba.csp.sentinel.adapter.web.common.UrlCleaner;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.sentinel.advice.SentinelBlockExceptionAdvice;
import io.github.atengk.cloud.sentinel.cleaner.AtengUrlCleaner;
import io.github.atengk.cloud.sentinel.datasource.SentinelNacosRuleSubscriber;
import io.github.atengk.cloud.sentinel.endpoint.SentinelBlockCounter;
import io.github.atengk.cloud.sentinel.endpoint.SentinelPanoramaEndpoint;
import io.github.atengk.cloud.sentinel.handler.AtengBlockExceptionHandler;
import io.github.atengk.cloud.sentinel.parser.AtengRequestOriginParser;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Sentinel 流量防卫增强底座自动装配
 *
 * @author Ateng
 * @since 2026-10-29
 */
@AutoConfiguration
@ConditionalOnClass({BlockException.class, BlockExceptionHandler.class})
@ConditionalOnProperty(prefix = "ateng.cloud.sentinel", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengSentinelProperties.class)
public class AtengSentinelAutoConfiguration {

    /**
     * 注册轻量原子阻断计数器 (无外部网络 IO 与锁竞争)
     */
    @Bean
    @ConditionalOnMissingBean
    public SentinelBlockCounter sentinelBlockCounter() {
        return new SentinelBlockCounter();
    }

    /**
     * 注册统一 Spring MVC 阻断异常处理器
     */
    @Bean
    @ConditionalOnMissingBean(BlockExceptionHandler.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.sentinel.block-handler", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AtengBlockExceptionHandler atengBlockExceptionHandler(
            ObjectProvider<ObjectMapper> objectMapperProvider,
            AtengSentinelProperties properties,
            ObjectProvider<SentinelBlockCounter> counterProvider) {
        return new AtengBlockExceptionHandler(
                objectMapperProvider.getIfAvailable(ObjectMapper::new),
                properties,
                counterProvider.getIfAvailable());
    }

    /**
     * 注册 RESTful URL 路径归一化清洗器 (防范 Sentinel 资源树 OOM)
     */
    @Bean
    @ConditionalOnMissingBean(UrlCleaner.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.sentinel.url-clean", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AtengUrlCleaner atengUrlCleaner(AtengSentinelProperties properties) {
        return new AtengUrlCleaner(properties);
    }

    /**
     * 注册多级请求调用源解析器
     */
    @Bean
    @ConditionalOnMissingBean(RequestOriginParser.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.sentinel.origin-parser", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AtengRequestOriginParser atengRequestOriginParser(AtengSentinelProperties properties) {
        return new AtengRequestOriginParser(properties);
    }

    /**
     * 注册 @SentinelResource 未捕获异常全局切面兜底
     */
    @Bean
    @ConditionalOnMissingBean(SentinelBlockExceptionAdvice.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.sentinel.block-advice", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SentinelBlockExceptionAdvice sentinelBlockExceptionAdvice(
            AtengSentinelProperties properties,
            ObjectProvider<SentinelBlockCounter> counterProvider) {
        return new SentinelBlockExceptionAdvice(properties, counterProvider.getIfAvailable());
    }

    /**
     * Sentinel 全景健康与规则自省 Actuator 端点自动装配 (仅在存在 Actuator 时生效)
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.sentinel.endpoint", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class SentinelPanoramaEndpointConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SentinelPanoramaEndpoint sentinelPanoramaEndpoint(
                ObjectProvider<AtengSentinelProperties> propertiesProvider,
                ObjectProvider<SentinelNacosRuleSubscriber> subscriberProvider,
                ObjectProvider<SentinelBlockCounter> counterProvider,
                Environment environment) {
            return new SentinelPanoramaEndpoint(propertiesProvider, subscriberProvider, counterProvider, environment);
        }
    }
}
