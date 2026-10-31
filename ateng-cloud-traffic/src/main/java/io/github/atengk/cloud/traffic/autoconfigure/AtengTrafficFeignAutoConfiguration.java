package io.github.atengk.cloud.traffic.autoconfigure;

import feign.RequestInterceptor;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.interceptor.TrafficFeignRequestInterceptor;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Feign 出站流量染色拦截器独立自动装配配置
 *
 * <p>核心装配契约：
 * 1. 独立运行适配：当微服务仅引入 {@code ateng-cloud-traffic} 与 OpenFeign 时，自动装配 {@link TrafficFeignRequestInterceptor}；
 * 2. 平滑让位契约：当检测到应用已引入全量 {@code ateng-cloud-feign} 且已装配 {@code feignContextPropagationInterceptor} 时，
 *    本装配类自动让位退化，避免同名或重复标头拦截器叠加执行，达成 Starter 零冲突与正交解耦。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@AutoConfiguration
@AutoConfigureAfter(name = "io.github.atengk.cloud.feign.autoconfigure.AtengFeignAutoConfiguration")
@ConditionalOnClass(RequestInterceptor.class)
@ConditionalOnProperty(prefix = TrafficConstants.PROPERTIES_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(name = "feignContextPropagationInterceptor")
@EnableConfigurationProperties(AtengTrafficProperties.class)
public class AtengTrafficFeignAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengTrafficFeignAutoConfiguration.class);

    public AtengTrafficFeignAutoConfiguration() {
        log.info("[ateng-cloud-traffic] Feign 出站流量染色拦截器独立装配已激活 (TrafficFeignRequestInterceptor active).");
    }

    /**
     * 注册 Feign 出站流量染色透传拦截器 Bean
     *
     * @param properties 流量治理配置属性
     * @return TrafficFeignRequestInterceptor
     */
    @Bean(name = "trafficFeignRequestInterceptor")
    @ConditionalOnMissingBean(TrafficFeignRequestInterceptor.class)
    public TrafficFeignRequestInterceptor trafficFeignRequestInterceptor(AtengTrafficProperties properties) {
        return new TrafficFeignRequestInterceptor(properties);
    }
}
