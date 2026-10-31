package io.github.atengk.cloud.traffic.autoconfigure;

import io.github.atengk.cloud.traffic.aspect.TrafficTagAspect;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextSnapshotProvider;
import io.github.atengk.cloud.traffic.filter.TrafficContextFilter;
import io.github.atengk.cloud.traffic.interceptor.TrafficClientHttpRequestInterceptor;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.rule.hash.StickyHashPartitioner;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficPredicateMatcher;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 全链路流量治理与灰度调度自动装配配置类
 *
 * @author Ateng
 * @since 2026-10-29
 */
@AutoConfiguration
@EnableConfigurationProperties(AtengTrafficProperties.class)
@ConditionalOnProperty(prefix = TrafficConstants.PROPERTIES_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengTrafficAutoConfiguration {

    /**
     * 注册 Servlet 运行时入站流量染色过滤器
     *
     * @param properties 流量治理配置属性
     * @return 过滤器注册 Bean
     */
    @Bean(name = "trafficContextFilter")
    @ConditionalOnMissingBean(name = "trafficContextFilter")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<TrafficContextFilter> trafficContextFilter(
            AtengTrafficProperties properties,
            ObjectProvider<TrafficRuleMatcher> ruleMatcherProvider,
            ObjectProvider<TrafficRuleProvider> ruleProvider) {
        FilterRegistrationBean<TrafficContextFilter> registration = new FilterRegistrationBean<>(
                new TrafficContextFilter(properties, ruleMatcherProvider::getIfAvailable, ruleProvider::getIfAvailable)
        );
        registration.setOrder(properties.getFilterOrder());
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * 注册异步线程池快照提供者 Bean (同时已通过 ServiceLoader 注册至 AsyncContext)
     *
     * @return 异步上下文快照提供者
     */
    @Bean
    @ConditionalOnMissingBean
    public TrafficContextSnapshotProvider trafficContextSnapshotProvider() {
        return new TrafficContextSnapshotProvider();
    }

    /**
     * 注册出站 HTTP 请求 (RestTemplate) 流量标签透传拦截器
     *
     * @param properties 流量治理配置属性
     * @return TrafficClientHttpRequestInterceptor
     */
    @Bean
    @ConditionalOnMissingBean
    public TrafficClientHttpRequestInterceptor trafficClientHttpRequestInterceptor(AtengTrafficProperties properties) {
        return new TrafficClientHttpRequestInterceptor(properties);
    }

    /**
     * 注册 RestTemplate 自动定制器，将流量透传拦截器附加至 RestTemplate 实例
     *
     * @param interceptor 出站透传拦截器
     * @return RestTemplateCustomizer
     */
    @Bean
    @ConditionalOnMissingBean(name = "trafficRestTemplateCustomizer")
    @ConditionalOnClass(RestTemplateCustomizer.class)
    public RestTemplateCustomizer trafficRestTemplateCustomizer(TrafficClientHttpRequestInterceptor interceptor) {
        return restTemplate -> {
            if (!restTemplate.getInterceptors().contains(interceptor)) {
                restTemplate.getInterceptors().add(interceptor);
            }
        };
    }

    /**
     * 注册一致性哈希粘性切流分发器 Bean
     *
     * @return StickyHashPartitioner
     */
    @Bean
    @ConditionalOnMissingBean
    public StickyHashPartitioner stickyHashPartitioner() {
        return new StickyHashPartitioner();
    }

    /**
     * 注册轻量谓词匹配器 Bean
     *
     * @return TrafficPredicateMatcher
     */
    @Bean
    @ConditionalOnMissingBean
    public TrafficPredicateMatcher trafficPredicateMatcher() {
        return new TrafficPredicateMatcher();
    }

    /**
     * 注册流量规则决策引擎 Bean
     *
     * @param stickyHashPartitioner 哈希切流分发器
     * @param predicateMatcher      谓词匹配器
     * @return TrafficRuleMatcher
     */
    @Bean
    @ConditionalOnMissingBean
    public TrafficRuleMatcher trafficRuleMatcher(StickyHashPartitioner stickyHashPartitioner,
                                                 TrafficPredicateMatcher predicateMatcher) {
        return new TrafficRuleMatcher(stickyHashPartitioner, predicateMatcher);
    }

    /**
     * 注册声明式流量染色与泳道绑定切面 Bean
     *
     * @param environment Spring 运行环境
     * @return TrafficTagAspect
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ProceedingJoinPoint.class)
    public TrafficTagAspect trafficTagAspect(Environment environment) {
        return new TrafficTagAspect(environment);
    }
}
